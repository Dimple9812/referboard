package com.referboard.referral.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkedInUrlsTest {

    @Test
    void buildsEncodedPeopleSearch() {
        assertThat(LinkedInUrls.peopleSearch("Northwind  IIT Madras & co"))
                .isEqualTo("https://www.linkedin.com/search/results/people/?keywords=Northwind%20IIT%20Madras%20%26%20co"
                        + "&origin=GLOBAL_SEARCH_HEADER");
    }

    @Test
    void normalisesLinkedInProfileUrls() {
        assertThat(LinkedInUrls.normalizeProfileUrl("in.linkedin.com/in/asha-rao-123/?utm=x#top"))
                .contains("https://www.linkedin.com/in/asha-rao-123");
        assertThat(LinkedInUrls.normalizeProfileUrl("HTTP://LinkedIn.com/in/asha/"))
                .contains("https://www.linkedin.com/in/asha");
    }

    @Test
    void rejectsNonProfileLinkedInUrlsAndGarbage() {
        assertThat(LinkedInUrls.normalizeProfileUrl("https://www.linkedin.com/company/northwind")).isEmpty();
        assertThat(LinkedInUrls.normalizeProfileUrl("https://www.linkedin.com/in/a/detail/contact-info")).isEmpty();
        assertThat(LinkedInUrls.normalizeProfileUrl("not a url")).isEmpty();
        assertThat(LinkedInUrls.normalizeProfileUrl(" ")).isEmpty();
    }

    @Test
    void acceptsOtherHttpsLinks() {
        assertThat(LinkedInUrls.normalizeProfileUrl("https://github.com/asha")).contains("https://github.com/asha");
    }
}
