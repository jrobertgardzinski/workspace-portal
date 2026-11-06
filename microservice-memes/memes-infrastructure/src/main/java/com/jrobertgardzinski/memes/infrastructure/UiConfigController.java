package com.jrobertgardzinski.memes.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Where the gallery's BROWSER should send its API calls — decided by the deployment, at runtime.
 *
 * <p><strong>The problem this solves.</strong> memes-ui reads its base URLs from
 * {@code import.meta.env.VITE_*}, which Vite substitutes at BUILD time and bakes into the bundle.
 * The bundle is packed into this service's jar, so one image carries one set of addresses — and they
 * were compose's ({@code http://localhost:8080} for security). Served from a Kubernetes cluster, the
 * very same page told the browser to call localhost:8080, which is the developer's own machine: the
 * gallery came up, looked healthy, and could not sign anyone in. An image whose behaviour is fixed
 * at build time cannot be deployed twice.
 *
 * <p><strong>Why a script and not JSON.</strong> This is fetched by a plain
 * {@code <script src="/ui-config.js">} in {@code index.html}, which the browser runs BEFORE the
 * module bundle (classic scripts run during parsing; {@code type="module"} is deferred). So the
 * values are simply there when the first module reads them — no bootstrap fetch, no async
 * initialisation, and no rewriting of every call site into a promise.
 *
 * <p><strong>It stays a fallback chain, not a replacement.</strong> The UI reads
 * {@code window.__PORTAL_CONFIG__} first, then the baked {@code VITE_*} value, then the compose
 * default. So {@code npm run dev} against a local stack keeps working with no server at all, and a
 * deployment that sets nothing behaves exactly as it did before this class existed.
 *
 * <p>Not cached: the whole point is that it follows the environment, and it is one small request per
 * page load against a service that serves images.
 */
@RestController
class UiConfigController {

    /**
     * Compose's addresses as defaults, deliberately: those are what the bundle already baked, so a
     * deployment that sets none of these is indistinguishable from one without this endpoint.
     */
    private final Map<String, String> urls;

    UiConfigController(
            @Value("${memes.ui.security-url:http://localhost:8080}") String securityUrl,
            @Value("${memes.ui.comments-url:http://localhost:8085}") String commentsUrl,
            @Value("${memes.ui.collections-url:http://localhost:8092}") String collectionsUrl) {
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("securityUrl", securityUrl);
        configured.put("commentsUrl", commentsUrl);
        configured.put("collectionsUrl", collectionsUrl);
        this.urls = Map.copyOf(configured);
    }

    @GetMapping(path = "/ui-config.js", produces = "application/javascript")
    ResponseEntity<String> uiConfig() {
        String body = "window.__PORTAL_CONFIG__ = {" + urls.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + quoted(entry.getValue()))
                .collect(Collectors.joining(",")) + "};";
        return ResponseEntity.ok()
                // no store: an address change must take effect on reload, not after a cache expires
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.valueOf("application/javascript"))
                .body(body);
    }

    /**
     * These values are configuration, not user input, so this is not a security boundary — but a URL
     * carrying a quote or a newline would produce a broken script that fails silently in the browser
     * and looks exactly like the misconfiguration this endpoint exists to end. Escaped so a typo is
     * a wrong address rather than a blank page.
     */
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "").replace("\r", "") + '"';
    }
}
