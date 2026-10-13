package com.referboard.events;

/**
 * Kafka topic names. Each topic carries exactly one event type, versioned in the name
 * so a breaking change ships as a new topic (e.g. {@code job.upserted.v2}) while old
 * consumers drain the old one.
 *
 * <p>Message key conventions (guarantees per-entity ordering):
 * <ul>
 *   <li>user-scoped events are keyed by {@code userId}</li>
 *   <li>job-scoped events are keyed by {@code jobId}</li>
 * </ul>
 */
public final class Topics {

    /** auth-service → notification-service. Key: userId. */
    public static final String USER_REGISTERED = "user.registered.v1";

    /** profile-service → job-service, resume-analyzer-service. Key: userId. */
    public static final String PROFILE_UPDATED = "profile.updated.v1";

    /** ingestion-service → job-service. Key: jobId. */
    public static final String JOB_UPSERTED = "job.upserted.v1";

    /** ingestion-service → job-service. Key: jobId. */
    public static final String JOB_CLOSED = "job.closed.v1";

    /** job-service → notification-service (once a day). Key: userId. */
    public static final String DAILY_MATCH_DIGEST = "match.daily-digest.v1";

    /** referral-service → tracker-service. Key: userId. */
    public static final String REFERRAL_STATUS_CHANGED = "referral.status-changed.v1";

    /** referral-service → notification-service. Key: userId. */
    public static final String REFERRAL_REMINDER_DUE = "referral.reminder-due.v1";

    /** resume-analyzer-service → tracker-service. Key: userId. */
    public static final String RESUME_VERSION_CREATED = "resume.version-created.v1";

    /** tracker-service → notification-service (and future analytics). Key: userId. */
    public static final String APPLICATION_STATUS_CHANGED = "application.status-changed.v1";

    private Topics() {
    }
}
