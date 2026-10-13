package com.referboard.analyzer.resume;

import com.referboard.analyzer.client.ProfileClient;
import com.referboard.analyzer.domain.ResumeVersion;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.repository.ResumeVersionRepository;
import com.referboard.analyzer.service.Upstream;
import com.referboard.common.web.ApiException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Loads the resume the user picked: one of their uploaded resumes (default: primary) or a tailored version. */
@Component
public class ResumeLoader {

    private static final Logger log = LoggerFactory.getLogger(ResumeLoader.class);

    private final ProfileClient profile;
    private final ResumeVersionRepository versions;
    private final VersionContent versionContent;

    public ResumeLoader(ProfileClient profile, ResumeVersionRepository versions, VersionContent versionContent) {
        this.profile = profile;
        this.versions = versions;
        this.versionContent = versionContent;
    }

    public LoadedResume load(UUID userId, LoadedResume.Source source, UUID id) {
        if (source == LoadedResume.Source.VERSION) {
            if (id == null) {
                throw ApiException.badRequest("VERSION_REQUIRED", "Pick which tailored resume to compare.");
            }
            ResumeVersion v = versions.findByIdAndUserId(id, userId)
                    .orElseThrow(() -> ApiException.notFound("Resume version", id));
            ResumeContent content = versionContent.read(v);
            return new LoadedResume(source, v.getId(), v.getName(), content, content.toPlainText(), null, null);
        }
        List<ProfileClient.ResumeInfo> resumes = Upstream.call(profile::resumes);
        ProfileClient.ResumeInfo info = id != null
                ? resumes.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Resume", id))
                : resumes.stream().filter(ProfileClient.ResumeInfo::primary).findFirst()
                .or(() -> resumes.stream().filter(r -> "PARSED".equals(r.parseStatus()))
                        .max(Comparator.comparing(ProfileClient.ResumeInfo::createdAt)))
                .orElseThrow(() -> ApiException.badRequest("NO_RESUME", "Upload a resume first."));
        if (!"PARSED".equals(info.parseStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESUME_NOT_READY",
                    "That resume is still being read (" + info.parseStatus() + "). Try again in a moment.");
        }
        ProfileClient.ParsedResumeResponse parsed = Upstream.call(() -> profile.parsed(info.id()));
        ResumeContent content = toContent(parsed.parsed());
        String text = parsed.text() == null || parsed.text().isBlank() ? content.toPlainText() : parsed.text();
        return new LoadedResume(source, info.id(), info.fileName(), content, text, pageCount(info), info.contentType());
    }

    /** Page count from the file's metadata (PDF exact, DOCX as last saved); null if unknown. Best effort. */
    private Integer pageCount(ProfileClient.ResumeInfo info) {
        if (info.contentType() == null || !(info.contentType().contains("pdf") || info.contentType().contains("word"))) {
            return null;
        }
        try {
            byte[] bytes = Upstream.call(() -> profile.file(info.id()));
            Metadata md = new Metadata();
            new AutoDetectParser().parse(new ByteArrayInputStream(bytes), new BodyContentHandler(-1), md,
                    new ParseContext());
            for (String key : List.of("xmpTPg:NPages", "meta:page-count", "Page-Count")) {
                String v = md.get(key);
                if (v != null && v.matches("\\d+")) {
                    return Integer.parseInt(v);
                }
            }
        } catch (Exception e) {
            log.debug("Couldn't read page count of resume {}: {}", info.id(), e.toString());
        }
        return null;
    }

    static ResumeContent toContent(ProfileClient.ParsedResume p) {
        if (p == null) {
            return new ResumeContent(null, null, null, null, null, null, null, null, null, null, null);
        }
        List<ResumeContent.Experience> exps = new ArrayList<>();
        if (p.experiences() != null) {
            for (ProfileClient.ParsedExperience e : p.experiences()) {
                List<String> bullets = e.bullets() != null && !e.bullets().isEmpty() ? e.bullets()
                        : splitDescription(e.description());
                exps.add(new ResumeContent.Experience(e.company(), e.title(), e.location(), e.startDate(),
                        e.endDate(), Boolean.TRUE.equals(e.isCurrent()), bullets));
            }
        }
        List<ResumeContent.Education> eds = new ArrayList<>();
        if (p.educations() != null) {
            p.educations().forEach(ed -> eds.add(new ResumeContent.Education(ed.institution(), ed.degree(),
                    ed.fieldOfStudy(), ed.startYear(), ed.endYear(), ed.grade())));
        }
        return new ResumeContent(p.fullName(), p.email(), p.phone(), p.location(), p.headline(), p.summary(),
                p.skills(), exps, eds, p.certifications(), p.links());
    }

    /** A role written as a paragraph becomes one bullet per sentence, so it can be scored and edited. */
    static List<String> splitDescription(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : description.split("(?<=[.;])\\s+|\\n+")) {
            String t = s.replaceFirst("^[-•*]\\s*", "").trim();
            if (t.length() > 3) {
                out.add(t);
            }
        }
        return out;
    }
}
