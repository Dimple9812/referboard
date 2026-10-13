package com.referboard.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.web.ApiException;
import com.referboard.profile.domain.Resume;
import com.referboard.profile.mapper.ProfileMapper;
import com.referboard.profile.parsing.ParsedResume;
import com.referboard.profile.parsing.TextExtractor;
import com.referboard.profile.repository.ResumeRepository;
import com.referboard.profile.storage.FileStorage;
import com.referboard.profile.web.dto.ProfileDtos.ParsedResumeResponse;
import com.referboard.profile.web.dto.ProfileDtos.ResumeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final Map<String, String> EXTENSIONS = Map.of(
            TextExtractor.PDF, "pdf", TextExtractor.DOCX, "docx", TextExtractor.DOC, "doc", TextExtractor.TXT, "txt");

    private final ResumeRepository resumes;
    private final FileStorage storage;
    private final TextExtractor extractor;
    private final ProfileService profileService;
    private final ProfileMapper mapper;
    private final ApplicationEventPublisher springEvents;
    private final ObjectMapper json;
    private final Clock clock;
    private final int maxResumes;

    public ResumeService(ResumeRepository resumes, FileStorage storage, TextExtractor extractor,
                         ProfileService profileService, ProfileMapper mapper, ApplicationEventPublisher springEvents,
                         ObjectMapper json, Clock clock,
                         @Value("${referboard.profile.max-resumes-per-user:10}") int maxResumes) {
        this.resumes = resumes;
        this.storage = storage;
        this.extractor = extractor;
        this.profileService = profileService;
        this.mapper = mapper;
        this.springEvents = springEvents;
        this.json = json;
        this.clock = clock;
        this.maxResumes = maxResumes;
    }

    public record UploadResult(ResumeResponse resume, boolean created) {
    }

    /**
     * Stores the file and queues parsing (202). The first resume becomes primary automatically; later ones only if
     * {@code makePrimary}. Re-uploading an identical file returns the existing resume instead of a duplicate.
     */
    @Transactional
    public UploadResult upload(UUID userId, String originalName, byte[] content, boolean makePrimary) {
        if (content.length == 0) {
            throw ApiException.badRequest("EMPTY_FILE", "The uploaded file is empty");
        }
        String fileName = safeFileName(originalName);
        String mediaType = extractor.detect(content, fileName);
        String sha256 = sha256(content);

        var existing = resumes.findFirstByUserIdAndSha256(userId, sha256);
        if (existing.isPresent()) {
            if (makePrimary && !existing.get().isPrimary()) {
                return new UploadResult(setPrimary(userId, existing.get().getId()), false);
            }
            return new UploadResult(mapper.toDto(existing.get()), false);
        }
        if (resumes.countByUserId(userId) >= maxResumes) {
            throw ApiException.conflict("RESUME_LIMIT", "You can keep up to " + maxResumes
                    + " resumes. Delete one to upload another.");
        }

        UUID id = UUID.randomUUID();
        String key = "users/%s/resumes/%s.%s".formatted(userId, id, EXTENSIONS.get(mediaType));
        storage.put(key, content, mediaType);
        deleteFileIfRolledBack(key);

        Resume resume = Resume.uploaded(id, userId, fileName, mediaType, content.length, sha256, key);
        boolean primary = makePrimary || resumes.findByUserIdAndPrimaryTrue(userId).isEmpty();
        if (primary) {
            resumes.clearPrimary(userId);
            resume.setPrimary(true);
        }
        resume = resumes.saveAndFlush(resume);
        springEvents.publishEvent(new ResumeUploaded(id, userId));
        log.info("Resume {} uploaded by {} ({} bytes, {}, primary={})", id, userId, content.length, mediaType, primary);
        return new UploadResult(mapper.toDto(resume), true);
    }

    @Transactional(readOnly = true)
    public List<ResumeResponse> list(UUID userId) {
        return mapper.toResumeDtos(resumes.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @Transactional(readOnly = true)
    public ResumeResponse get(UUID userId, UUID resumeId) {
        return mapper.toDto(owned(userId, resumeId));
    }

    public record FileContent(String fileName, String contentType, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public FileContent download(UUID userId, UUID resumeId) {
        Resume r = owned(userId, resumeId);
        return new FileContent(r.getFileName(), r.getContentType(), storage.get(r.getStorageKey()));
    }

    /** Structured parse + extracted text of one resume; used by the resume analyzer and the review screen. */
    @Transactional(readOnly = true)
    public ParsedResumeResponse parsed(UUID userId, UUID resumeId) {
        Resume r = owned(userId, resumeId);
        if (r.getParseStatus() != Resume.ParseStatus.PARSED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_PARSED",
                    "This resume is " + r.getParseStatus().name().toLowerCase() + "; try again shortly");
        }
        try {
            ParsedResume parsed = json.readValue(r.getParsedJson(), ParsedResume.class);
            return new ParsedResumeResponse(r.getId(), r.getParser().name(), r.getParserModel(), parsed,
                    r.getExtractedText());
        } catch (Exception e) {
            throw new IllegalStateException("Stored parse for resume " + resumeId + " is unreadable", e);
        }
    }

    /** Makes a resume primary; if it's already parsed, the profile is rebuilt from it right away. */
    @Transactional
    public ResumeResponse setPrimary(UUID userId, UUID resumeId) {
        Resume r = owned(userId, resumeId);
        if (!r.isPrimary()) {
            resumes.clearPrimary(userId);
            r.setPrimary(true);
            resumes.saveAndFlush(r);
            if (r.getParseStatus() == Resume.ParseStatus.PARSED) {
                try {
                    profileService.applyParsedResume(userId, r.getId(),
                            json.readValue(r.getParsedJson(), ParsedResume.class), r.getParser());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return mapper.toDto(r);
    }

    @Transactional
    public ResumeResponse reparse(UUID userId, UUID resumeId) {
        Resume r = owned(userId, resumeId);
        if (r.getParseStatus() == Resume.ParseStatus.PENDING) {
            return mapper.toDto(r);
        }
        r.resetForReparse();
        springEvents.publishEvent(new ResumeUploaded(r.getId(), userId));
        return mapper.toDto(r);
    }

    /** Soft-deletes the record and removes the file. The profile keeps what it learned until the next upload. */
    @Transactional
    public void delete(UUID userId, UUID resumeId) {
        Resume r = owned(userId, resumeId);
        r.softDelete(clock.instant());
        String key = r.getStorageKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(key);
                } catch (Exception e) {
                    log.warn("Could not delete stored file {}: {}", key, e.getMessage());
                }
            }
        });
    }

    private Resume owned(UUID userId, UUID resumeId) {
        // Same 404 whether it doesn't exist or belongs to someone else: never confirm other users' ids.
        return resumes.findByIdAndUserId(resumeId, userId).orElseThrow(() -> ApiException.notFound("Resume", resumeId));
    }

    private void deleteFileIfRolledBack(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        storage.delete(key);
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            }
        });
    }

    static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "resume";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[\\p{Cntrl}\"]", "").trim();
        return base.isEmpty() ? "resume" : base.substring(0, Math.min(base.length(), 200));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
