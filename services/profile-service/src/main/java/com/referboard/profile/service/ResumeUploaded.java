package com.referboard.profile.service;

import java.util.UUID;

/** In-process signal that a resume needs (re)parsing; handled after the upload transaction commits. */
public record ResumeUploaded(UUID resumeId, UUID userId) {
}
