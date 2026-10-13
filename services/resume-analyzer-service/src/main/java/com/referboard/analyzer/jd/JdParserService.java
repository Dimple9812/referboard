package com.referboard.analyzer.jd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.ai.JsonSchemas;
import com.referboard.ai.LlmClient;
import com.referboard.ai.LlmUnavailableException;
import com.referboard.ai.StructuredPrompt;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.skills.SkillTaxonomy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parses JDs with the LLM when configured, otherwise with {@link HeuristicJdParser}, and caches the result by the
 * JD's content hash so the same posting is never sent to the LLM twice. LLM output is checked against the text:
 * a skill the JD never mentions is dropped.
 */
@Service
public class JdParserService {

    private static final Logger log = LoggerFactory.getLogger(JdParserService.class);

    public static final String PROMPT_VERSION = "jd-v1";
    private static final String SYSTEM = JsonSchemas.loadText("prompts/jd-parse-system.txt");
    private static final JsonNode SCHEMA = JsonSchemas.load("prompts/jd-parse-schema.json");
    static final int MAX_SKILLS = 30;

    public enum Parser { LLM, HEURISTIC }

    public record Result(ParsedJd parsed, Parser parser, String model, boolean cached) {
    }

    private final LlmClient llm;
    private final SkillTaxonomy taxonomy;
    private final HeuristicJdParser heuristic;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdParserService(LlmClient llm, SkillTaxonomy taxonomy, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.llm = llm;
        this.taxonomy = taxonomy;
        this.heuristic = new HeuristicJdParser(taxonomy);
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Result parse(String text, String hash, String titleHint, String companyHint) {
        Optional<Result> cached = cached(hash);
        if (cached.isPresent() && (cached.get().parser() == Parser.LLM || !llm.available())) {
            return withHints(cached.get(), titleHint, companyHint);
        }
        Result fresh;
        if (llm.available()) {
            try {
                fresh = new Result(llmParse(text), Parser.LLM, llm.modelId(), false);
            } catch (LlmUnavailableException e) {
                fresh = new Result(heuristic.parse(text, null, null), Parser.HEURISTIC, null, false);
            } catch (RuntimeException e) {
                log.warn("LLM JD parse failed, using heuristics: {}", e.toString());
                fresh = new Result(heuristic.parse(text, null, null), Parser.HEURISTIC, null, false);
            }
        } else {
            fresh = new Result(heuristic.parse(text, null, null), Parser.HEURISTIC, null, false);
        }
        store(hash, fresh);
        return withHints(fresh, titleHint, companyHint);
    }

    ParsedJd llmParse(String text) {
        ParsedJd raw = llm.structured(new StructuredPrompt<>(SYSTEM,
                "<JOB DESCRIPTION>\n" + text + "\n</JOB DESCRIPTION>", "record_job_requirements",
                "Record the job's requirements.", SCHEMA, ParsedJd.class, 2500, 0.0));
        return clean(raw, text);
    }

    /** Normalises skills to the taxonomy and drops any the JD text doesn't actually mention. */
    ParsedJd clean(ParsedJd raw, String text) {
        Set<String> mentioned = taxonomy.extract(text);
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> required = grounded(raw.requiredSkills(), mentioned, lower);
        List<String> preferred = new ArrayList<>(grounded(raw.preferredSkills(), mentioned, lower));
        preferred.removeAll(required);
        Integer min = raw.minYears() != null && raw.minYears() >= 0 && raw.minYears() <= 30 ? raw.minYears() : null;
        Integer max = raw.maxYears() != null && min != null && raw.maxYears() >= min && raw.maxYears() <= 40
                ? raw.maxYears() : null;
        return new ParsedJd(raw.title(), raw.company(), raw.seniority(), min, max, required, preferred,
                raw.responsibilities().stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                        .limit(12).toList(),
                raw.keywords().stream().filter(Objects::nonNull).map(k -> k.trim().toLowerCase(Locale.ROOT))
                        .filter(k -> !k.isEmpty() && lower.contains(k)).distinct().limit(20).toList(),
                raw.education(), raw.location(), raw.remote());
    }

    private List<String> grounded(List<String> skills, Set<String> mentioned, String lowerText) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : skills) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String canonical = taxonomy.normalize(s);
            boolean inText = mentioned.contains(canonical) || lowerText.contains(s.trim().toLowerCase(Locale.ROOT));
            if (inText) {
                out.add(canonical);
            } else {
                log.debug("Dropping JD skill '{}' not found in the text", s);
            }
        }
        return out.stream().limit(MAX_SKILLS).toList();
    }

    private Result withHints(Result r, String titleHint, String companyHint) {
        ParsedJd p = r.parsed();
        if ((titleHint == null || titleHint.isBlank()) && (companyHint == null || companyHint.isBlank())) {
            return r;
        }
        return new Result(new ParsedJd(titleHint != null && !titleHint.isBlank() ? titleHint : p.title(),
                companyHint != null && !companyHint.isBlank() ? companyHint : p.company(), p.seniority(),
                p.minYears(), p.maxYears(), p.requiredSkills(), p.preferredSkills(), p.responsibilities(),
                p.keywords(), p.education(), p.location(), p.remote()), r.parser(), r.model(), r.cached());
    }

    private Optional<Result> cached(String hash) {
        return jdbc.query("""
                        update parsed_jds set hit_count = hit_count + 1, last_used_at = now()
                        where content_hash = ? and prompt_version = ?
                        returning parser, model, parsed::text
                        """,
                (rs, i) -> {
                    try {
                        return new Result(mapper.readValue(rs.getString(3), ParsedJd.class),
                                Parser.valueOf(rs.getString(1)), rs.getString(2), true);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                }, hash, PROMPT_VERSION).stream().filter(Objects::nonNull).findFirst();
    }

    private void store(String hash, Result r) {
        try {
            jdbc.update("""
                            insert into parsed_jds (content_hash, parser, model, prompt_version, parsed)
                            values (?, ?, ?, ?, cast(? as jsonb))
                            on conflict (content_hash) do update set parser = excluded.parser, model = excluded.model,
                                prompt_version = excluded.prompt_version, parsed = excluded.parsed, last_used_at = now()
                            """,
                    hash, r.parser().name(), r.model(), PROMPT_VERSION, mapper.writeValueAsString(r.parsed()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
