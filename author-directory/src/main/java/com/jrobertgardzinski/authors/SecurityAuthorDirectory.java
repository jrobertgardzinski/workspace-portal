package com.jrobertgardzinski.authors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.identity.UserId;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The directory over security's {@code GET /users?ids=}, in batches of at most {@value #BATCH},
 * with a per-id cache. An absent id is cached too, so a deleted account costs one call per TTL,
 * not one per render. When security cannot be reached the answer is whatever the cache still
 * holds and {@code onFailure} is told — the listing goes on without names rather than failing.
 */
public final class SecurityAuthorDirectory implements AuthorDirectory {

    public static final int BATCH = 100;
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI users;
    private final Duration ttl;
    private final Clock clock;
    private final Consumer<Exception> onFailure;
    private final Map<UserId, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<AuthorName> name, Instant until) {
    }

    SecurityAuthorDirectory(HttpClient http, ObjectMapper mapper, String securityUrl, Duration ttl, Clock clock,
                            Consumer<Exception> onFailure) {
        this.http = http;
        this.mapper = mapper;
        this.users = URI.create(securityUrl.endsWith("/") ? securityUrl + "users" : securityUrl + "/users");
        this.ttl = ttl;
        this.clock = clock;
        this.onFailure = onFailure;
    }

    public static SecurityAuthorDirectory overHttp(String securityUrl, ObjectMapper mapper, Duration ttl, Clock clock,
                                                   Consumer<Exception> onFailure) {
        return new SecurityAuthorDirectory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), mapper,
                securityUrl, ttl, clock, onFailure);
    }

    @Override
    public Map<UserId, AuthorName> namesOf(Collection<UserId> ids) {
        Instant now = clock.instant();
        Map<UserId, AuthorName> answer = new LinkedHashMap<>();
        List<UserId> missing = new ArrayList<>();
        for (UserId id : new LinkedHashSet<>(ids)) {
            Cached cached = cache.get(id);
            if (cached != null && cached.until().isAfter(now)) {
                cached.name().ifPresent(name -> answer.put(id, name));
            } else {
                missing.add(id);
            }
        }
        for (int from = 0; from < missing.size(); from += BATCH) {
            List<UserId> batch = missing.subList(from, Math.min(from + BATCH, missing.size()));
            fetch(batch).ifPresent(fetched -> {
                Instant until = now.plus(ttl);
                for (UserId id : batch) {
                    Optional<AuthorName> name = Optional.ofNullable(fetched.get(id));
                    cache.put(id, new Cached(name, until));
                    name.ifPresent(n -> answer.put(id, n));
                }
            });
        }
        return answer;
    }

    /** One call to security; empty when it could not be made, so nothing is cached about the batch. */
    private Optional<Map<UserId, AuthorName>> fetch(List<UserId> batch) {
        String query = batch.stream().map(UserId::toString).collect(Collectors.joining(","));
        HttpRequest request = HttpRequest.newBuilder(URI.create(users + "?ids=" + query))
                .timeout(TIMEOUT).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GET /users answered " + response.statusCode());
            }
            Map<UserId, AuthorName> names = new LinkedHashMap<>();
            for (JsonNode entry : mapper.readTree(response.body())) {
                names.put(UserId.of(entry.path("id").asText()), new AuthorName(entry.path("displayName").asText()));
            }
            return Optional.of(names);
        } catch (IOException | RuntimeException unavailable) {
            onFailure.accept(unavailable);
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            onFailure.accept(interrupted);
            return Optional.empty();
        }
    }
}
