package com.referboard.ingestion.source;

import reactor.core.publisher.Mono;

import java.util.List;

/** A provider of postings. Implementations only call documented public APIs. */
public interface JobSource {

    SourceType type();

    /** Whether this source is configured to run (e.g. Adzuna needs API keys). */
    boolean enabled();

    /** Postings currently listed for the target. Errors propagate; the orchestrator records them per source. */
    Mono<List<RawPosting>> fetch(SourceTarget target);
}
