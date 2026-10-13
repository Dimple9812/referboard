package com.referboard.profile.service;

import com.referboard.ai.EmbeddingClient;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.events.ProfileUpdatedEvent;
import com.referboard.profile.domain.Experience;
import com.referboard.profile.domain.JobPreferences;
import com.referboard.profile.domain.Profile;
import com.referboard.profile.domain.ProfileSkill;
import com.referboard.profile.repository.JobPreferencesRepository;
import com.referboard.profile.repository.ProfileEmbeddingRepository;
import com.referboard.profile.repository.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * After a profile change commits: compute the embedding (outside any DB transaction — it may call an external API),
 * then store it and publish {@code ProfileUpdated} atomically, but only if the profile hasn't changed again in the
 * meantime. Rapid successive edits therefore produce one event per surviving version, never a stale one.
 */
@Service
public class ProfileSyncService {

    private static final Logger log = LoggerFactory.getLogger(ProfileSyncService.class);

    private final ProfileRepository profiles;
    private final JobPreferencesRepository preferences;
    private final ProfileEmbeddingRepository embeddings;
    private final EmbeddingClient embeddingClient;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public ProfileSyncService(ProfileRepository profiles, JobPreferencesRepository preferences,
                              ProfileEmbeddingRepository embeddings, EmbeddingClient embeddingClient,
                              EventPublisher events, EventJson eventJson, PlatformTransactionManager txManager) {
        this.profiles = profiles;
        this.preferences = preferences;
        this.embeddings = embeddings;
        this.embeddingClient = embeddingClient;
        this.events = events;
        this.eventJson = eventJson;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileChanged(ProfileChanged change) {
        try {
            sync(change.userId());
        } catch (Exception e) {
            log.error("Profile sync failed for user {}", change.userId(), e);
        }
    }

    /** Package-visible for tests; returns true if an event was published. */
    boolean sync(UUID userId) {
        Snapshot snapshot = readTx.execute(status -> profiles.findById(userId).map(this::snapshot).orElse(null));
        if (snapshot == null) {
            return false;
        }
        float[] vector = null;
        try {
            vector = embeddingClient.embed(snapshot.embeddingText());
        } catch (Exception e) {
            log.warn("Embedding failed for user {} (publishing without vector): {}", userId, e.getMessage());
        }
        float[] finalVector = vector;
        Boolean published = writeTx.execute(status -> {
            if (!embeddings.storeIfCurrent(userId, finalVector, embeddingClient.model(), snapshot.version())) {
                log.debug("Profile {} moved past version {}; skipping stale sync", userId, snapshot.version());
                return false;
            }
            events.publish(new ProfileUpdatedEvent(eventJson.newMetadata("ProfileUpdated"), userId,
                    snapshot.version(), snapshot.headline(), snapshot.skills(), snapshot.profile().totalExperienceYears(),
                    snapshot.profile().college(), snapshot.profile().pastCompanies(), snapshot.preferences(),
                    finalVector, finalVector == null ? null : embeddingClient.model(),
                    finalVector == null ? 0 : finalVector.length));
            return true;
        });
        return Boolean.TRUE.equals(published);
    }

    private record ProfileFacts(java.math.BigDecimal totalExperienceYears, String college, List<String> pastCompanies) {
    }

    private record Snapshot(long version, String headline, List<String> skills, ProfileFacts profile,
                            ProfileUpdatedEvent.Preferences preferences, String embeddingText) {
    }

    private Snapshot snapshot(Profile p) {
        JobPreferences prefs = preferences.findById(p.getUserId()).orElseGet(() -> JobPreferences.defaults(p.getUserId()));
        List<String> skills = p.getSkills().stream().map(ProfileSkill::getName).toList();
        List<String> companies = p.getExperiences().stream().map(Experience::getCompany).distinct().toList();
        return new Snapshot(p.getProfileVersion(), p.getHeadline(), skills,
                new ProfileFacts(p.getTotalExperienceYears(), p.getCollege(), companies),
                new ProfileUpdatedEvent.Preferences(List.copyOf(prefs.getRoles()), List.copyOf(prefs.getLocations()),
                        prefs.isRemoteOnly(), prefs.isOpenToRemote(), prefs.getMinSalaryInr(),
                        prefs.getExperienceLevel() == null ? null : prefs.getExperienceLevel().name()),
                embeddingText(p, prefs));
    }

    /**
     * What the profile "means" for matching: target roles and headline first, then skills and the substance of
     * recent roles. Kept well under embedding input limits.
     */
    static String embeddingText(Profile p, JobPreferences prefs) {
        StringBuilder sb = new StringBuilder();
        if (!prefs.getRoles().isEmpty()) {
            sb.append("Target roles: ").append(String.join(", ", prefs.getRoles())).append('\n');
        }
        appendLine(sb, "Headline", p.getHeadline());
        appendLine(sb, "Current", join(p.getCurrentTitle(), p.getCurrentCompany()));
        if (p.getTotalExperienceYears() != null) {
            sb.append("Experience: ").append(p.getTotalExperienceYears()).append(" years\n");
        }
        sb.append("Skills: ").append(p.getSkills().stream().map(ProfileSkill::getDisplayName)
                .collect(Collectors.joining(", "))).append('\n');
        p.getExperiences().stream().limit(4).forEach(e -> {
            sb.append(e.getTitle()).append(" at ").append(e.getCompany()).append(": ");
            e.getBullets().stream().limit(6).forEach(b -> sb.append(b).append(' '));
            sb.append('\n');
        });
        appendLine(sb, "Summary", p.getSummary());
        return sb.length() > 8000 ? sb.substring(0, 8000) : sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " at " + b;
    }
}
