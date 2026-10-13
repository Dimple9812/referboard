package com.referboard.profile.parsing;

import com.referboard.profile.testdata.Documents;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicResumeParserTest {

    private final HeuristicResumeParser parser = new HeuristicResumeParser(SkillTaxonomy.defaultTaxonomy());

    static String sample() {
        return Documents.sampleResumeText();
    }

    @Test
    void parsesContactDetails() throws Exception {
        ParsedResume r = parser.parse(sample());

        assertThat(r.fullName()).isEqualTo("Priya Sharma");
        assertThat(r.email()).isEqualTo("priya.sharma@example.com");
        assertThat(r.phone()).isEqualTo("+91 98765 43210");
        assertThat(r.links()).contains("linkedin.com/in/priya-sharma-dev", "github.com/priyasharma");
        assertThat(r.summary()).startsWith("Backend engineer with 4+ years");
    }

    @Test
    void parsesExperienceWithDatesBulletsAndHeaderOnEitherLine() throws Exception {
        List<ParsedResume.ParsedExperience> exps = parser.parse(sample()).experiences();

        assertThat(exps).hasSize(2);
        ParsedResume.ParsedExperience razorpay = exps.get(0);
        assertThat(razorpay.title()).isEqualTo("Senior Software Engineer");
        assertThat(razorpay.company()).isEqualTo("Razorpay");
        assertThat(razorpay.startDate()).isEqualTo("2023-01");
        assertThat(razorpay.isCurrent()).isTrue();
        assertThat(razorpay.endDate()).isNull();
        assertThat(razorpay.location()).isEqualTo("Bengaluru");
        assertThat(razorpay.bullets()).hasSize(3).first().asString().startsWith("Built Spring Boot microservices");

        ParsedResume.ParsedExperience flipkart = exps.get(1);
        assertThat(flipkart.title()).isEqualTo("Software Engineer");
        assertThat(flipkart.company()).isEqualTo("Flipkart");
        assertThat(flipkart.startDate()).isEqualTo("2020-07");
        assertThat(flipkart.endDate()).isEqualTo("2022-12");
        assertThat(flipkart.bullets()).hasSize(2);
    }

    @Test
    void parsesEducation() throws Exception {
        List<ParsedResume.ParsedEducation> edu = parser.parse(sample()).educations();

        assertThat(edu).singleElement().satisfies(e -> {
            assertThat(e.institution()).isEqualTo("Indian Institute of Technology, Roorkee");
            assertThat(e.degree()).isEqualTo("B.Tech");
            assertThat(e.fieldOfStudy()).isEqualTo("Computer Science");
            assertThat(e.startYear()).isEqualTo(2016);
            assertThat(e.endYear()).isEqualTo(2020);
            assertThat(e.grade()).isEqualTo("8.4/10");
        });
    }

    @Test
    void collectsSkillsFromSkillsSectionAndBulletsUsingCanonicalDisplayNames() throws Exception {
        List<String> skills = parser.parse(sample()).skills();

        assertThat(skills).contains("Java", "Spring Boot", "Microservices", "Apache Kafka", "PostgreSQL", "Redis",
                "Kubernetes", "AWS", "React", "Helm", "GitHub Actions", "JUnit", "Mockito");
        assertThat(skills).doesNotHaveDuplicates();
    }

    @Test
    void neverInventsMissingData() {
        ParsedResume r = parser.parse("Just a line of text without any recognisable resume structure at all.");

        assertThat(r.experiences()).isEmpty();
        assertThat(r.educations()).isEmpty();
        assertThat(r.email()).isNull();
        assertThat(r.headline()).isNull();
    }

    @Test
    void understandsCommonDateFormats() {
        assertThat(HeuristicResumeParser.yearMonth("Sept 2021")).isEqualTo("2021-09");
        assertThat(HeuristicResumeParser.yearMonth("Mar '19")).isEqualTo("2019-03");
        assertThat(HeuristicResumeParser.yearMonth("06/2018")).isEqualTo("2018-06");
        assertThat(HeuristicResumeParser.yearMonth("2017")).isEqualTo("2017-01");
        assertThat(HeuristicResumeParser.yearMonth("someday")).isNull();
    }
}
