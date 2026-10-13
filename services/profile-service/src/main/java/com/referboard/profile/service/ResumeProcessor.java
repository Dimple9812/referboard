package com.referboard.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.web.ApiException;
import com.referboard.profile.domain.Resume;
import com.referboard.profile.parsing.ResumeParser;
import com.referboard.profile.parsing.TextExtractor;
import com.referboard.profile.repository.ResumeRepository;
import com.referboard.profile.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Background pipeline for one resume: load file → extract text (Tika) → parse (LLM or heuristic) → store result →
 * if it's the primary resume, rebuild the profile. Slow steps run outside DB transactions; the final write is one
 * short transaction. Any failure marks the resume FAILED with a message the UI can show.
 */
@Service
public class ResumeProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResumeProcessor.class);

    private final ResumeRepository resumes;
    private final FileStorage storage;
    private final TextExtractor extractor;
    private final ResumeParser parser;
    private final ProfileService profileService;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ResumeProcessor(ResumeRepository resumes, FileStorage storage, TextExtractor extractor, ResumeParser parser,
                           ProfileService profileService, ObjectMapper json, PlatformTransactionManager txManager,
                           Clock clock) {
        this.resumes = resumes;
        this.storage = storage;
        this.extractor = extractor;
        this.parser = parser;
        this.profileService = profileService;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResumeUploaded(ResumeUploaded event) {
        process(event.resumeId());
    }

    public void process(UUID resumeId) {
        Resume resume = tx.execute(s -> resumes.findById(resumeId).orElse(null));
        if (resume == null) {
            return; // deleted before processing started
        }
        long started = System.currentTimeMillis();
        try {
            byte[] bytes = storage.get(resume.getStorageKey());
            TextExtractor.Extracted extracted = extractor.extract(bytes, resume.getFileName());
            ResumeParser.Result result = parser.parse(extracted.text());
            String parsedJson = json.writeValueAsString(result.resume());

            tx.executeWithoutResult(s -> resumes.findById(resumeId).ifPresent(r -> {
                r.markParsed(extracted.text(), result.parser(), result.model(), parsedJson, clock.instant());
                resumes.save(r);
                if (r.isPrimary()) {
                    profileService.applyParsedResume(r.getUserId(), r.getId(), result.resume(), result.parser());
                }
            }));
            log.info("Parsed resume {} with {} in {} ms ({} experiences, {} skills)", resumeId, result.model(),
                    System.currentTimeMillis() - started, result.resume().experiences().size(),
                    result.resume().skills().size());
        } catch (ApiException e) {
            fail(resumeId, e.getMessage());
        } catch (Exception e) {
            log.error("Resume {} processing failed", resumeId, e);
            fail(resumeId, "We couldn't process this resume. Please try again or upload a different format.");
        }
    }

    private void fail(UUID resumeId, String message) {
        tx.executeWithoutResult(s -> resumes.findById(resumeId).ifPresent(r -> {
            r.markFailed(message);
            resumes.save(r);
        }));
    }
}
