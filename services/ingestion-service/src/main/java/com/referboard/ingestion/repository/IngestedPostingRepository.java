package com.referboard.ingestion.repository;

import com.referboard.ingestion.domain.IngestedPosting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface IngestedPostingRepository extends JpaRepository<IngestedPosting, UUID> {

    List<IngestedPosting> findByJobIdIn(Collection<UUID> jobIds);

    /**
     * Open postings not seen since {@code cutoff} whose source was read successfully <em>after</em> they were last
     * seen — i.e. the board was reachable and the job was really gone. A board that's down closes nothing.
     */
    @Query(value = """
            select p.* from ingested_postings p
              join sources s on s.source_key = p.source_key
             where p.closed_at is null
               and p.last_seen_at < :cutoff
               and s.last_success_at > p.last_seen_at
             order by p.last_seen_at
             limit :limit
            """, nativeQuery = true)
    List<IngestedPosting> findStale(Instant cutoff, int limit);

    long countByClosedAtIsNull();
}
