package com.referboard.ingestion.normalize;

import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.source.RawPosting;
import com.referboard.ingestion.source.SourceType;
import com.referboard.skills.SkillTaxonomy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * RawPosting → NormalizedJob, or a rejection reason. Applies the market filters (India + remote-open-to-India,
 * tech roles only), builds the de-dup key and the content hash.
 *
 * <p>De-dup key: {@code normalizedCompany | normalizedTitle | locationKey}. The job id is
 * {@code UUID.nameUUIDFromBytes(key)}, so the same job from Greenhouse and Adzuna maps to one id across runs and
 * across services.
 */
@Component
public class JobNormalizer {

    public sealed interface Result permits Kept, Dropped {
    }

    public record Kept(NormalizedJob job) implements Result {
    }

    public record Dropped(String reason) implements Result {
    }

    private final SkillTaxonomy skills;
    private final IngestionProperties.Filters filters;

    public JobNormalizer(SkillTaxonomy skills, IngestionProperties props) {
        this.skills = skills;
        this.filters = props.filters();
    }

    public Result normalize(RawPosting raw) {
        if (blank(raw.title()) || blank(raw.companyName()) || blank(raw.applyUrl()) || !raw.applyUrl().startsWith("http")) {
            return new Dropped("incomplete");
        }
        if (filters.techRolesOnly() && !RoleClassifier.isTechRole(raw.title())) {
            return new Dropped("non-tech");
        }
        LocationNormalizer.Location loc = LocationNormalizer.normalize(raw.locationText(), raw.remote(), raw.countryHint());
        boolean inTargetCountry = loc.country() != null && filters.countries().contains(loc.country());
        boolean acceptableRemote = filters.includeRemote() && loc.remote() && loc.remoteOpenToIndia();
        if (!inTargetCountry && !acceptableRemote) {
            return new Dropped("location");
        }

        String title = raw.title().trim().replaceAll("\\s+", " ");
        String company = raw.companyName().trim();
        String normalizedTitle = RoleClassifier.normalizeTitle(title);
        String dedupKey = RoleClassifier.normalizeCompany(company) + "|" + normalizedTitle + "|" + loc.key();
        UUID jobId = UUID.nameUUIDFromBytes(dedupKey.getBytes(StandardCharsets.UTF_8));

        String text = raw.descriptionText() == null ? "" : raw.descriptionText();
        List<String> jobSkills = List.copyOf(skills.extract(title + "\n" + text));
        String html = Html.sanitize(raw.descriptionHtml());

        SourceType type = SourceType.valueOf(raw.source().toUpperCase(Locale.ROOT));
        String hash = sha256(String.join("\u0001", Objects.toString(title), company, Objects.toString(loc.display()),
                String.valueOf(loc.remote()), Objects.toString(raw.employmentType()), text, raw.applyUrl(),
                Objects.toString(raw.salaryMin()), Objects.toString(raw.salaryMax())));

        return new Kept(new NormalizedJob(jobId, truncate(dedupKey, 700), type, raw.sourceKey(), raw.sourceJobId(),
                truncate(company, 200), raw.companySlug(), truncate(title, 300), normalizedTitle,
                truncate(loc.display(), 300), loc.country(), loc.remote(), raw.employmentType(),
                RoleClassifier.seniority(title).name(), raw.department(), html, text, raw.applyUrl(), raw.postedAt(),
                raw.salaryMin(), raw.salaryMax(), raw.salaryCurrency(), jobSkills, hash));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
