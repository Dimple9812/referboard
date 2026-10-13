package com.referboard.ingestion.service;

import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.domain.Source;
import com.referboard.ingestion.repository.SourceRepository;
import com.referboard.ingestion.source.AdzunaSource;
import com.referboard.ingestion.source.JobSource;
import com.referboard.ingestion.source.SampleSource;
import com.referboard.ingestion.source.SourceTarget;
import com.referboard.ingestion.source.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps the {@code sources} table in sync with the seed file (companies.yml) and the configured Adzuna queries,
 * and answers "what should this run fetch?". Fetch health (status, last success) lives only in the table, so
 * editing the YAML never resets it.
 */
@Service
public class SourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistry.class);
    private static final Pattern SLUG = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    public record SeedCompany(String name, SourceType ats, String slug, boolean enabled) {
    }

    private final SourceRepository sources;
    private final ResourceLoader resources;
    private final IngestionProperties props;
    private final Map<SourceType, JobSource> byType = new EnumMap<>(SourceType.class);
    private final AdzunaSource adzuna;

    public SourceRegistry(SourceRepository sources, ResourceLoader resources, IngestionProperties props,
                          List<JobSource> jobSources, AdzunaSource adzuna) {
        this.sources = sources;
        this.resources = resources;
        this.props = props;
        this.adzuna = adzuna;
        jobSources.forEach(s -> byType.put(s.type(), s));
    }

    public JobSource sourceFor(SourceType type) {
        return byType.get(type);
    }

    /** Upserts seed companies and aggregator targets. Returns the number of entries in the seed file. */
    @Transactional
    public int sync() {
        List<SeedCompany> seed = loadSeed(props.seedFile());
        Map<String, Source> existing = new LinkedHashMap<>();
        sources.findAll().forEach(s -> existing.put(s.getSourceKey(), s));

        List<Source> toSave = new ArrayList<>();
        for (SeedCompany c : seed) {
            SourceTarget t = SourceTarget.board(c.ats(), c.slug(), c.name());
            Source s = existing.remove(t.sourceKey());
            if (s == null) {
                s = Source.of(t.sourceKey(), c.ats(), c.slug(), c.name());
            }
            s.setDisplayName(c.name());
            s.setEnabled(c.enabled());
            toSave.add(s);
        }
        for (SourceTarget t : adzuna.targets()) {
            Source s = existing.remove(t.sourceKey());
            if (s == null) {
                s = Source.of(t.sourceKey(), SourceType.ADZUNA, t.slug(), t.displayName());
            }
            s.setEnabled(adzuna.enabled());
            toSave.add(s);
        }
        Source sample = existing.remove(SampleSource.TARGET.sourceKey());
        if (sample == null) {
            sample = Source.of(SampleSource.TARGET.sourceKey(), SourceType.SAMPLE, SampleSource.TARGET.slug(),
                    SampleSource.TARGET.displayName());
        }
        sample.setEnabled(props.sample().enabled());
        toSave.add(sample);

        // Removed from the seed file: keep the row (its postings still reference it) but stop fetching.
        existing.values().forEach(s -> s.setEnabled(false));
        toSave.addAll(existing.values());
        sources.saveAll(toSave);
        log.info("Source registry synced: {} seed companies, {} Adzuna queries (enabled={}), sample={}",
                seed.size(), adzuna.targets().size(), adzuna.enabled(), props.sample().enabled());
        return seed.size();
    }

    /** Enabled sources whose fetcher is configured and which aren't in a back-off window. */
    @Transactional(readOnly = true)
    public List<SourceTarget> dueTargets(Instant now) {
        return sources.findByEnabledTrueOrderBySourceKey().stream()
                .filter(s -> s.dueForFetch(now, props.retryNotFoundAfter()))
                .filter(s -> {
                    JobSource js = byType.get(s.getSourceType());
                    return js != null && js.enabled();
                })
                .map(s -> new SourceTarget(s.getSourceKey(), s.getSourceType(), s.getSlug(), s.getDisplayName()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    List<SeedCompany> loadSeed(String location) {
        Resource resource = resources.getResource(location);
        if (!resource.exists()) {
            log.warn("Seed file {} not found; no ATS boards will be fetched", location);
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            List<Map<String, Object>> entries = (List<Map<String, Object>>) root.getOrDefault("companies", List.of());
            Map<String, SeedCompany> unique = new LinkedHashMap<>();
            for (Map<String, Object> e : entries) {
                String name = String.valueOf(e.get("name")).trim();
                String slug = String.valueOf(e.get("slug")).trim();
                SourceType ats;
                try {
                    ats = SourceType.valueOf(String.valueOf(e.get("ats")).trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    log.warn("Seed entry {} has unknown ats '{}'; skipped", name, e.get("ats"));
                    continue;
                }
                if (!List.of(SourceType.GREENHOUSE, SourceType.LEVER, SourceType.ASHBY).contains(ats)
                        || !SLUG.matcher(slug).matches()) {
                    log.warn("Seed entry {} ({}:{}) is invalid; skipped", name, ats, slug);
                    continue;
                }
                boolean enabled = !Boolean.FALSE.equals(e.get("enabled"));
                unique.putIfAbsent(ats + ":" + slug.toLowerCase(Locale.ROOT), new SeedCompany(name, ats, slug, enabled));
            }
            return List.copyOf(unique.values());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + location, e);
        }
    }
}
