package com.referboard.profile.parsing;

import com.fasterxml.jackson.databind.JsonNode;
import com.referboard.ai.JsonSchemas;
import com.referboard.ai.LlmCallException;
import com.referboard.ai.LlmClient;
import com.referboard.ai.LlmUnavailableException;
import com.referboard.ai.StructuredPrompt;
import com.referboard.profile.domain.Resume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns extracted resume text into {@link ParsedResume}: Claude/OpenAI with a strict JSON schema when configured,
 * otherwise (or if the LLM call fails) the deterministic {@link HeuristicResumeParser}. The result records which
 * parser produced it so the UI can ask users to double-check heuristic output.
 */
@Component
public class ResumeParser {

    private static final Logger log = LoggerFactory.getLogger(ResumeParser.class);
    private static final JsonNode SCHEMA = JsonSchemas.load("prompts/resume-schema.json");
    private static final String SYSTEM = JsonSchemas.loadText("prompts/resume-parse-system.txt");

    private final LlmClient llm;
    private final HeuristicResumeParser heuristic;
    private final int maxChars;

    public ResumeParser(LlmClient llm, HeuristicResumeParser heuristic,
                        @Value("${referboard.profile.max-text-chars:30000}") int maxChars) {
        this.llm = llm;
        this.heuristic = heuristic;
        this.maxChars = maxChars;
    }

    public record Result(ParsedResume resume, Resume.Parser parser, String model) {
    }

    public Result parse(String text) {
        if (llm.available()) {
            try {
                String clipped = text.length() > maxChars ? text.substring(0, maxChars) : text;
                ParsedResume parsed = llm.structured(new StructuredPrompt<>(SYSTEM,
                        "<resume>\n" + clipped + "\n</resume>",
                        "record_resume", "Record the structured contents of the resume", SCHEMA,
                        ParsedResume.class, 8000, 0.0));
                return new Result(sanitize(parsed), Resume.Parser.LLM, llm.modelId());
            } catch (LlmUnavailableException e) {
                // fall through
            } catch (LlmCallException e) {
                log.warn("LLM resume parsing failed, using heuristic parser: {}", e.getMessage());
            }
        }
        return new Result(heuristic.parse(text), Resume.Parser.HEURISTIC, "heuristic-v1");
    }

    /** Drop structurally useless entries the model may return (e.g. an experience with no company and title). */
    static ParsedResume sanitize(ParsedResume r) {
        var experiences = r.experiences().stream()
                .filter(e -> notBlank(e.company()) || notBlank(e.title()))
                .toList();
        var educations = r.educations().stream().filter(e -> notBlank(e.institution())).toList();
        var skills = r.skills().stream().filter(ResumeParser::notBlank).map(String::trim).distinct().toList();
        return new ParsedResume(r.fullName(), r.email(), r.phone(), r.location(), r.headline(), r.summary(), skills,
                experiences, educations, r.certifications(), r.links());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
