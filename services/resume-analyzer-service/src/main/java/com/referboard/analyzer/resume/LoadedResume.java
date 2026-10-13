package com.referboard.analyzer.resume;

import com.referboard.analyzer.model.ResumeContent;

import java.util.UUID;

/**
 * A resume ready to score.
 *
 * @param text      what an ATS would read: the uploaded file's extracted text, or the rendered text of a version
 * @param pageCount pages of the uploaded file when known (PDF, DOCX), else null
 */
public record LoadedResume(Source source, UUID refId, String name, ResumeContent content, String text,
                           Integer pageCount, String contentType) {

    public enum Source { PROFILE_RESUME, VERSION }
}
