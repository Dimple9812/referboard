package com.referboard.referral.service;

import com.referboard.referral.Fixtures;
import com.referboard.referral.domain.PersonType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionServiceTest {

    @Test
    void roleWordsDropLevelAndQualifiers() {
        assertThat(SuggestionService.roleWords("Senior Backend Engineer (Java), Payments")).isEqualTo("Backend Engineer");
        assertThat(SuggestionService.roleWords("SDE II - Platform")).isEqualTo("SDE");
        assertThat(SuggestionService.roleWords("Staff Data Engineer")).isEqualTo("Data Engineer");
    }

    @Test
    void suggestsEveryPersonTypeWithSearchLinks() {
        var s = SuggestionService.build(Fixtures.job(), Fixtures.user());
        assertThat(s.people()).extracting(SuggestionService.Suggestion::personType).containsExactly(
                PersonType.RECRUITER, PersonType.ENGINEERING_MANAGER, PersonType.TEAM_MEMBER, PersonType.ALUMNI,
                PersonType.EX_COLLEAGUE, PersonType.EX_COLLEAGUE);
        assertThat(s.people()).allSatisfy(p ->
                assertThat(p.linkedinSearchUrl()).startsWith("https://www.linkedin.com/search/results/people/?keywords="));
        assertThat(s.people().get(2).searchKeywords()).isEqualTo("Northwind Backend Engineer");
        assertThat(s.people().get(3).searchKeywords()).isEqualTo("Northwind IIT Madras");
        assertThat(s.people().get(4).searchKeywords()).isEqualTo("Northwind Flipkart");
        assertThat(s.notice()).contains("never visits LinkedIn");

        var u = Fixtures.user();
        u.setCollege("Indian Institute of Technology, Roorkee");
        assertThat(SuggestionService.build(Fixtures.job(), u).people().get(3).searchKeywords())
                .isEqualTo("Northwind Indian Institute of Technology Roorkee");
    }

    @Test
    void skipsAlumniWithoutCollegeAndThePostingCompanyItself() {
        var user = Fixtures.user();
        user.setCollege(null);
        user.setPastCompanies(List.of("Northwind"));
        var s = SuggestionService.build(Fixtures.job(), user);
        assertThat(s.people()).extracting(SuggestionService.Suggestion::personType)
                .doesNotContain(PersonType.ALUMNI, PersonType.EX_COLLEAGUE);
    }
}
