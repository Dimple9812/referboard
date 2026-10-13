package com.referboard.ingestion.source;

public enum SourceType {
    GREENHOUSE(3), LEVER(3), ASHBY(3), ADZUNA(2), SAMPLE(1);

    /**
     * When two sources report the same job (same de-dup key), the higher-priority one owns the content: an ATS
     * board has the full description and the canonical apply link; Adzuna only has a snippet.
     */
    private final int priority;

    SourceType(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
