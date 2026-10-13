package com.referboard.ingestion.http;

import com.referboard.ingestion.source.SourceFetchException;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoliteHttpClientTest {

    private MockWebServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private volatile String robots = "User-agent: *\nDisallow: /private\n";
    private volatile int robotsStatus = 200;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                hits.computeIfAbsent(path.split("\\?")[0], k -> new AtomicInteger()).incrementAndGet();
                if (path.equals("/robots.txt")) {
                    return new MockResponse().setResponseCode(robotsStatus).setBody(robots);
                }
                if (path.startsWith("/ok")) {
                    return json("{\"jobs\":[]}");
                }
                if (path.startsWith("/flaky")) {
                    int n = hits.get("/flaky").get();
                    return n < 3 ? new MockResponse().setResponseCode(503) : json("{\"ok\":true}");
                }
                if (path.startsWith("/limited")) {
                    return new MockResponse().setResponseCode(429);
                }
                if (path.startsWith("/missing")) {
                    return new MockResponse().setResponseCode(404);
                }
                return new MockResponse().setResponseCode(400);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private PoliteHttpClient client() {
        return new PoliteHttpClient(WebClient.builder().build(), "ReferBoardBot/0.1 (+test)", 50, 4,
                Duration.ofMillis(5), Clock.systemUTC());
    }

    private URI uri(String path) {
        return server.url(path).uri();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void fetchesAllowedPathsAndCachesRobots() {
        PoliteHttpClient http = client();

        assertThat(http.getJson(uri("/ok/1")).block().path("jobs").isArray()).isTrue();
        http.getJson(uri("/ok/2")).block();

        assertThat(hits.get("/robots.txt").get()).as("robots.txt fetched once per host").isEqualTo(1);
    }

    @Test
    void respectsRobotsDisallow() {
        assertThatThrownBy(() -> client().getJson(uri("/private/data")).block())
                .isInstanceOf(SourceFetchException.class)
                .extracting(e -> ((SourceFetchException) e).kind())
                .isEqualTo(SourceFetchException.Kind.BLOCKED_BY_ROBOTS);
        assertThat(hits).doesNotContainKey("/private/data");
    }

    @Test
    void unreachableRobotsMeansDoNotCrawl() {
        robotsStatus = 503;
        assertThatThrownBy(() -> client().getJson(uri("/ok/1")).block())
                .hasMessageContaining("could not be read")
                .extracting(e -> ((SourceFetchException) e).kind())
                .isEqualTo(SourceFetchException.Kind.BLOCKED_BY_ROBOTS);
        assertThat(hits).doesNotContainKey("/ok/1");
    }

    @Test
    void missingRobotsMeansAllowed() {
        robotsStatus = 404;
        assertThat(client().getJson(uri("/ok/1")).block()).isNotNull();
    }

    @Test
    void retriesTransientFailuresWithBackoff() {
        assertThat(client().getJson(uri("/flaky")).block().path("ok").asBoolean()).isTrue();
        assertThat(hits.get("/flaky").get()).isEqualTo(3);
    }

    @Test
    void givesUpAfterMaxAttemptsOn429() {
        assertThatThrownBy(() -> client().getJson(uri("/limited")).block())
                .extracting(e -> ((SourceFetchException) e).kind())
                .isEqualTo(SourceFetchException.Kind.TRANSIENT);
        assertThat(hits.get("/limited").get()).isEqualTo(4);
    }

    @Test
    void notFoundIsNotRetried() {
        assertThatThrownBy(() -> client().getJson(uri("/missing/board")).block())
                .extracting(e -> ((SourceFetchException) e).kind())
                .isEqualTo(SourceFetchException.Kind.NOT_FOUND);
        assertThat(hits.get("/missing/board").get()).isEqualTo(1);
    }

    @Test
    void redactsApiKeysInMessages() {
        assertThat(PoliteHttpClient.redact(URI.create("https://api.adzuna.com/v1/x?app_id=abc&app_key=secret&what=java")))
                .isEqualTo("https://api.adzuna.com/v1/x?app_id=***&app_key=***&what=java");
    }
}
