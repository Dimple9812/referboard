package com.referboard.ingestion.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTxtTest {

    @Test
    void wildcardGroupAppliesWhenNoSpecificGroup() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /private/
                Allow: /private/public-page
                """, "ReferBoardBot");

        assertThat(robots.isAllowed("/v1/boards/acme/jobs")).isTrue();
        assertThat(robots.isAllowed("/private/data")).isFalse();
        assertThat(robots.isAllowed("/private/public-page")).as("longer allow wins").isTrue();
    }

    @Test
    void specificGroupOverridesWildcard() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /

                User-agent: ReferBoardBot
                User-agent: OtherBot
                Disallow: /admin
                """, "ReferBoardBot");

        assertThat(robots.isAllowed("/v0/postings/acme")).isTrue();
        assertThat(robots.isAllowed("/admin/x")).isFalse();
    }

    @Test
    void supportsWildcardsAndEndAnchor() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /*?content=true
                Disallow: /*.pdf$
                """, "ReferBoardBot");

        assertThat(robots.isAllowed("/v1/boards/acme/jobs?content=true")).isFalse();
        assertThat(robots.isAllowed("/v1/boards/acme/jobs")).isTrue();
        assertThat(robots.isAllowed("/files/a.pdf")).isFalse();
        assertThat(robots.isAllowed("/files/a.pdf?x=1")).isTrue();
    }

    @Test
    void emptyDisallowAllowsEverythingAndCommentsAreIgnored() {
        RobotsTxt robots = RobotsTxt.parse("""
                # nothing blocked
                User-agent: * # everyone
                Disallow:
                """, "ReferBoardBot");

        assertThat(robots.isAllowed("/anything")).isTrue();
        assertThat(RobotsTxt.disallowAll().isAllowed("/x")).isFalse();
    }
}
