package com.referboard.ingestion.normalize;

import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.source.RawPosting;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizationTest {

    @Nested
    class Locations {

        @ParameterizedTest
        @CsvSource(delimiter = ';', value = {
                "Bangalore, Karnataka, India; bengaluru; IN; false; false",
                "Bengaluru; bengaluru; IN; false; false",
                "Gurgaon, Haryana; gurugram; IN; false; false",
                "Gurugram, Delhi NCR; gurugram; IN; false; false",
                "Remote - India; remote|in; IN; true; true",
                "Remote; remote; ; true; true",
                "Anywhere in the world; remote; ; true; true",
                "Remote - US; remote|other; ; true; false",
                "San Francisco, CA; san francisco ca; ; false; false",
                "Bengaluru (Hybrid) / Remote; bengaluru|remote; IN; true; true",
        })
        void normalisesCommonFormats(String raw, String key, String country, boolean remote, boolean openToIndia) {
            var loc = LocationNormalizer.normalize(raw, null, null);
            assertThat(loc.key()).isEqualTo(key);
            assertThat(loc.country()).isEqualTo(country);
            assertThat(loc.remote()).isEqualTo(remote);
            assertThat(loc.remoteOpenToIndia()).isEqualTo(openToIndia);
        }

        @Test
        void explicitRemoteFlagAndForeignCountryHint() {
            var usRemote = LocationNormalizer.normalize("Remote", true, "US");
            assertThat(usRemote.remote()).isTrue();
            assertThat(usRemote.remoteOpenToIndia()).as("Lever says remote, but the posting's country is US").isFalse();

            var flagged = LocationNormalizer.normalize("Pune", true, null);
            assertThat(flagged.remote()).isTrue();
            assertThat(flagged.country()).isEqualTo("IN");
        }
    }

    @Nested
    class Roles {

        @ParameterizedTest
        @CsvSource({
                "Senior Backend Engineer, true", "Data Scientist II, true", "SDE-2, true", "QA Automation Lead, true",
                "Product Designer, true", "Enterprise Account Executive, false", "Talent Acquisition Partner, false",
                "Sales Engineer, true", "Marketing Data Scientist, true", "Customer Success Manager, false"})
        void classifiesTechRoles(String title, boolean tech) {
            assertThat(RoleClassifier.isTechRole(title)).isEqualTo(tech);
        }

        @ParameterizedTest
        @CsvSource({
                "Software Engineering Intern, INTERN", "Junior Developer, ENTRY", "SDE 1, ENTRY",
                "Backend Engineer, MID", "Sr. Software Engineer, SENIOR", "SDE III, SENIOR",
                "Staff Engineer, LEAD", "Engineering Manager, LEAD", "Principal Architect, PRINCIPAL",
                "Director of Engineering, PRINCIPAL"})
        void infersSeniority(String title, RoleClassifier.Seniority expected) {
            assertThat(RoleClassifier.seniority(title)).isEqualTo(expected);
        }

        @Test
        void normalisesTitlesForDedup() {
            assertThat(RoleClassifier.normalizeTitle("Sr. Backend Engineer - Bengaluru"))
                    .isEqualTo(RoleClassifier.normalizeTitle("Senior Backend Engineer"))
                    .isEqualTo("senior backend engineer");
            assertThat(RoleClassifier.normalizeTitle("Backend Engineer (Remote, India)")).isEqualTo("backend engineer");
            assertThat(RoleClassifier.normalizeTitle("Backend Engineer (Java)")).isEqualTo("backend engineer java");
            assertThat(RoleClassifier.normalizeTitle("C++ / C# Developer")).isEqualTo("c++ c# developer");
            assertThat(RoleClassifier.normalizeTitle("Node.js Engineer")).isEqualTo("node.js engineer");
        }

        @Test
        void normalisesCompanyLegalNames() {
            assertThat(RoleClassifier.normalizeCompany("Razorpay Software Private Limited")).isEqualTo("razorpay");
            assertThat(RoleClassifier.normalizeCompany("Razorpay")).isEqualTo("razorpay");
            assertThat(RoleClassifier.normalizeCompany("Zeta (Better World Technology Pvt. Ltd.)"))
                    .isEqualTo("zeta better world");
        }
    }

    @Nested
    class Jobs {

        private final JobNormalizer normalizer = new JobNormalizer(SkillTaxonomy.defaultTaxonomy(),
                new IngestionProperties(null, false, "Bot", null, 1, 1, 1, null, null, null, null,
                        new IngestionProperties.Filters(List.of("IN"), true, true), null, null, null, null, null));

        private RawPosting raw(String source, String company, String title, String location, Boolean remote,
                               String country) {
            return new RawPosting(source, source + ":x", "1", company, null, title, location, remote, country,
                    "Full-time", "Engineering", "<p>Build <b>Java</b> &amp; Kafka services on K8s.</p>",
                    "Build Java & Kafka services on K8s.", "https://example.com/apply", Instant.parse("2026-10-01T00:00:00Z"),
                    null, null, null);
        }

        @Test
        void sameJobFromAtsAndAggregatorGetsTheSameId() {
            var fromGreenhouse = normalizer.normalize(raw("greenhouse", "Razorpay", "Senior Backend Engineer",
                    "Bengaluru, India", null, null));
            var fromAdzuna = normalizer.normalize(raw("adzuna", "Razorpay Software Private Limited",
                    "Sr. Backend Engineer - Bangalore", "Bangalore, Karnataka", null, "IN"));

            assertThat(fromGreenhouse).isInstanceOf(JobNormalizer.Kept.class);
            assertThat(fromAdzuna).isInstanceOf(JobNormalizer.Kept.class);
            NormalizedJob a = ((JobNormalizer.Kept) fromGreenhouse).job();
            NormalizedJob b = ((JobNormalizer.Kept) fromAdzuna).job();
            assertThat(a.dedupKey()).isEqualTo("razorpay|senior backend engineer|bengaluru");
            assertThat(a.jobId()).isEqualTo(b.jobId());
        }

        @Test
        void extractsSkillsAndSeniorityAndSanitises() {
            NormalizedJob job = ((JobNormalizer.Kept) normalizer.normalize(raw("lever", "Acme", "Senior Java Engineer",
                    "Pune", null, null))).job();

            assertThat(job.skills()).containsExactly("java", "kafka", "kubernetes");
            assertThat(job.seniority()).isEqualTo("SENIOR");
            assertThat(job.country()).isEqualTo("IN");
            assertThat(job.contentHash()).hasSize(64);
        }

        @Test
        void appliesMarketFilters() {
            assertThat(normalizer.normalize(raw("greenhouse", "Acme", "Backend Engineer", "Austin, TX", false, "US")))
                    .isEqualTo(new JobNormalizer.Dropped("location"));
            assertThat(normalizer.normalize(raw("greenhouse", "Acme", "Backend Engineer", "Remote - EMEA", null, null)))
                    .isEqualTo(new JobNormalizer.Dropped("location"));
            assertThat(normalizer.normalize(raw("greenhouse", "Acme", "Account Executive", "Mumbai", null, null)))
                    .isEqualTo(new JobNormalizer.Dropped("non-tech"));
            assertThat(normalizer.normalize(raw("greenhouse", "Acme", "Backend Engineer", "Remote", null, null)))
                    .isInstanceOf(JobNormalizer.Kept.class);
        }
    }

    @Nested
    class HtmlHandling {

        @Test
        void sanitisesUntrustedHtml() {
            String dirty = "<p onclick=\"steal()\">Hi <script>alert(1)</script><a href=\"javascript:x()\">x</a>"
                    + "<a href=\"https://ok.example\">apply</a></p><iframe src=\"https://evil\"></iframe><ul><li>A</li></ul>";
            String clean = Html.sanitize(dirty);

            assertThat(clean).doesNotContain("script", "onclick", "javascript:", "iframe")
                    .contains("<li>A</li>")
                    .contains("href=\"https://ok.example\"")
                    .contains("rel=\"noopener noreferrer nofollow\"");
        }

        @Test
        void decodesGreenhouseEncodedHtmlAndExtractsReadableText() {
            String encoded = "&lt;p&gt;We build &amp;amp; ship.&lt;/p&gt;&lt;ul&gt;&lt;li&gt;Java&lt;/li&gt;&lt;li&gt;Go&lt;/li&gt;&lt;/ul&gt;";
            String html = Html.unescapeIfEncoded(encoded);

            assertThat(html).startsWith("<p>");
            assertThat(Html.toText(html)).isEqualTo("We build & ship.\n• Java\n• Go");
        }
    }
}
