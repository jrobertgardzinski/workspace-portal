package com.jrobertgardzinski.authors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.identity.UserId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP adapter against a stand-in for security's GET /users?ids=, plus its cache and its failure mode. */
class SecurityAuthorDirectoryTest extends AuthorDirectoryContractTest {

    private final Map<UserId, String> security = new LinkedHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Exception> failures = new ArrayList<>();
    private boolean down;
    private Instant now = Instant.parse("2026-09-26T12:00:00Z");
    private HttpServer server;
    private SecurityAuthorDirectory directory;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/users", exchange -> {
            calls.incrementAndGet();
            if (down) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String ids = exchange.getRequestURI().getQuery().substring("ids=".length());
            String body = java.util.Arrays.stream(ids.split(","))
                    .map(UserId::of).filter(security::containsKey)
                    .map(id -> "{\"id\":\"" + id + "\",\"displayName\":\"" + security.get(id) + "\"}")
                    .collect(Collectors.joining(",", "[", "]"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        Clock clock = new Clock() {
            @Override
            public Instant instant() {
                return now;
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
        directory = new SecurityAuthorDirectory(HttpClient.newHttpClient(), new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort(), Duration.ofMinutes(1), clock, failures::add);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Override
    protected AuthorDirectory directory() {
        return directory;
    }

    @Override
    protected UserId givenAuthor(String email) {
        UserId id = UserId.random();
        security.put(id, email.charAt(0) + "***" + email.substring(email.indexOf('@')));
        return id;
    }

    @Test
    @DisplayName("a name is fetched once per TTL, an absent id too")
    void the_cache_holds_answers_and_absences() {
        UserId alice = givenAuthor("alice@example.com");
        UserId nobody = UserId.random();

        directory.namesOf(List.of(alice, nobody));
        directory.namesOf(List.of(alice, nobody));
        assertEquals(1, calls.get(), "both answers were cached, the absence included");

        now = now.plus(Duration.ofMinutes(2));
        directory.namesOf(List.of(alice));
        assertEquals(2, calls.get(), "past the TTL the id is asked again");
    }

    @Test
    @DisplayName("a batch over the limit goes out in several calls")
    void large_batches_are_split() {
        List<UserId> many = IntStream.range(0, 250).mapToObj(i -> givenAuthor("u" + i + "@example.com")).toList();

        assertEquals(250, directory.namesOf(many).size());
        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("security down: what the cache holds is answered, the rest is absent, and somebody is told")
    void when_security_is_down_the_listing_goes_on() {
        UserId alice = givenAuthor("alice@example.com");
        UserId bob = givenAuthor("bob@example.com");
        directory.namesOf(List.of(alice));

        down = true;
        Map<UserId, AuthorName> names = directory.namesOf(List.of(alice, bob));

        assertEquals(1, names.size(), "alice from the cache, bob unknown for now");
        assertTrue(names.containsKey(alice));
        assertEquals(1, failures.size());

        down = false;
        assertEquals(2, directory.namesOf(List.of(alice, bob)).size(), "nothing about the failed batch was cached");
    }
}
