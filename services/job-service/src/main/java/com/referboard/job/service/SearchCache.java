package com.referboard.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.job.config.JobsProperties;
import com.referboard.job.store.JobCardRow;
import com.referboard.job.store.SearchCriteria;
import com.referboard.job.store.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Caches the user-independent part of a search (the page of job cards + total) in Redis for a short TTL. Match %
 * is overlaid per user afterwards, so one cached page serves everyone. A Redis outage only costs latency: errors
 * are logged and the query runs uncached.
 */
@Component
public class SearchCache {

    private static final Logger log = LoggerFactory.getLogger(SearchCache.class);
    private static final String PREFIX = "jobs:search:v1:";

    record Cached(List<JobCardRow> items, long total) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public SearchCache(StringRedisTemplate redis, ObjectMapper mapper, JobsProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = props.searchCacheTtl();
    }

    public SearchRepository.Page getOrLoad(SearchCriteria criteria, Supplier<SearchRepository.Page> loader) {
        if (criteria.effectiveSort() == SearchCriteria.Sort.MATCH || ttl.isZero()) {
            return loader.get(); // per-user ordering: not shareable
        }
        String key = key(criteria);
        Optional<SearchRepository.Page> hit = read(key);
        if (hit.isPresent()) {
            return hit.get();
        }
        SearchRepository.Page page = loader.get();
        write(key, page);
        return page;
    }

    private Optional<SearchRepository.Page> read(String key) {
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }
            Cached c = mapper.readValue(value, Cached.class);
            return Optional.of(new SearchRepository.Page(c.items(), c.total()));
        } catch (Exception e) {
            log.warn("Search cache read failed ({}); querying database", e.getMessage());
            return Optional.empty();
        }
    }

    private void write(String key, SearchRepository.Page page) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(new Cached(page.items(), page.total())), ttl);
        } catch (Exception e) {
            log.warn("Search cache write failed: {}", e.getMessage());
        }
    }

    String key(SearchCriteria c) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(c).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
