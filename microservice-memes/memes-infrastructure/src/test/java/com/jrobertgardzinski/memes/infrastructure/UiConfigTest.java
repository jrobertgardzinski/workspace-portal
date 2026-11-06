package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The addresses the gallery's BROWSER is told to use — which the deployment decides, not the build.
 *
 * <p>Vite bakes {@code import.meta.env.VITE_*} into the bundle, the bundle is packed into this
 * service's jar, and the jar becomes one container image. So before {@code /ui-config.js} existed,
 * that image carried compose's addresses permanently: served from a Kubernetes cluster, the page
 * told the browser to call {@code localhost:8080} — the developer's own machine. The gallery loaded,
 * every probe stayed green, and nobody could sign in. This is the seam that lets one image be
 * deployed twice, so it is worth a test rather than a README line.
 */
@Epic("Infrastructure")
@Feature("UI runtime config")
class UiConfigTest {

    @Nested
    @SpringBootTest(classes = MemesApplication.class)
    @AutoConfigureMockMvc
    @DisplayName("with nothing configured")
    class ByDefault {

        @Autowired
        MockMvc http;

        @Test
        @DisplayName("it serves compose's addresses, so an existing deployment sees no change")
        void the_defaults_are_composes_addresses() throws Exception {
            http.perform(get("/ui-config.js"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "securityUrl:\"http://localhost:8080\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "commentsUrl:\"http://localhost:8085\"")));
        }

        @Test
        @DisplayName("and never lets a browser cache them — an address change must survive a reload")
        void the_answer_is_not_cacheable() throws Exception {
            http.perform(get("/ui-config.js"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                            "window.__PORTAL_CONFIG__")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        }
    }

    @Nested
    @SpringBootTest(classes = MemesApplication.class, properties = {
            // what k8s/base/memes.yaml sets: the ingress host names, not this laptop
            "memes.ui.security-url=http://security.portal.localhost:9080",
            "memes.ui.comments-url=http://comments.portal.localhost:9080",
            "memes.ui.collections-url=http://collections.portal.localhost:9080"})
    @AutoConfigureMockMvc
    @DisplayName("with a deployment's addresses")
    class WhenDeployed {

        @Autowired
        MockMvc http;

        @Test
        @DisplayName("it serves those instead — the whole point of the endpoint")
        void the_deployments_addresses_win() throws Exception {
            http.perform(get("/ui-config.js"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "securityUrl:\"http://security.portal.localhost:9080\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("localhost:8080"))));
        }
    }
}
