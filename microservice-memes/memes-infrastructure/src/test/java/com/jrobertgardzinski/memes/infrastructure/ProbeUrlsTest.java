package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two URLs Kubernetes actually calls — asserted over HTTP, which nothing did until now.
 *
 * <p>{@link ListenerHealthProbeGroupsTest} pins WHICH group the listener lamp belongs to and proves
 * the property names really build those groups. What neither it nor anything else touched is the
 * ADDRESS. {@code portal/k8s/base/memes.yaml} names {@code /actuator/health/readiness} and
 * {@code /actuator/health/liveness}, and a perfectly configured group can sit behind a path that
 * answers 404 — the path depends on {@code management.endpoints.web.exposure.include}, on the
 * actuator base path, and on the framework still serving groups as path segments at all.
 *
 * <p><strong>Why now.</strong> Spring Boot 4 moved the health API to a new package and split the
 * actuator into modules on 2026-07-29. The group configuration came through untouched and the older
 * test proved that; the URL was assumed on both sides of the migration. With k3s next, an assumed
 * probe URL is the sort of thing discovered by a pod that never goes ready.
 *
 * <p><strong>On the management port.</strong> Production serves these on 9083
 * ({@code management.server.port}), the main API on 8083 — this test drops that separation, because
 * MockMvc has no ports at all. So what is proven here is the PATH and the group's contents, not the
 * port split.
 *
 * <p>This paragraph used to add "portal/wait-healthy.sh and the compose healthcheck cover the port",
 * and that was false in the direction that matters: the compose healthcheck hardcodes 9083 and only
 * runs when somebody stands the stack up, so nothing at all compared the number this file ships with
 * the {@code containerPort} the k8s manifest probes. Two numbers in two files, and if they drift
 * NOTHING notices — every suite green, every gate green, and a pod that never becomes Ready. That is
 * not hypothetical here: Prometheus scraped memes on 8083 for a whole day after the actuator moved
 * to 9083, with no symptom but empty dashboards. comments grew a guard against exactly this on
 * 2026-07-29; {@link #the_manifest_probes_the_port_the_actuator_listens_on} is the same guard for
 * the service the story started in.
 *
 * <p>Asserts the group's own component rather than only the status code: a 200 carrying a
 * differently shaped body would still fail the probe's purpose.
 *
 * <p><strong>Exactly one property is set here, and it is not one of the ones under test.</strong>
 * This class used to hand itself the whole management block — the two group definitions AND
 * {@code exposure.include=health,prometheus} — duplicating
 * {@code src/main/resources/application.properties} instead of reading it. Annotation properties win
 * over the file, so the test proved its own five lines: drop {@code health} from the exposure line
 * that goes into the image and every assertion below still passed, while both probe URLs answered
 * 404 in the cluster and the pod never went Ready. (The commit that "verified these tests can fail"
 * verified a deletion from the test's list, not from the file's.) There is no
 * {@code application.properties} on this module's test classpath, so with the overrides gone the
 * context boots on the SHIPPED configuration and these three tests exercise it.
 *
 * <p>The survivor collapses {@code management.server.port} back onto the main one, because MockMvc
 * has no ports — that is the one thing this test genuinely cannot exercise, and it says so above.
 */
@Epic("Infrastructure")
@Feature("Health probes")
@SpringBootTest(classes = MemesApplication.class,
        // MockMvc drives the main context and has no ports; the port itself is pinned by
        // the_manifest_probes_the_port_the_actuator_listens_on below
        properties = "management.server.port=")
@AutoConfigureMockMvc
class ProbeUrlsTest {

    /** Relative to the MODULE directory, which is what surefire makes the working directory. */
    private static final java.nio.file.Path DEPLOYED_PROPERTIES =
            java.nio.file.Path.of("src/main/resources/application.properties");

    @Autowired
    MockMvc http;

    /**
     * The port those URLs answer on, against the manifest that probes them — the guard comments got
     * on 2026-07-29 and this service, which the story is about, did not.
     *
     * <p>Two halves, split the way P16 poz. 8 says they must be. That the shipped file sets a
     * management port AT ALL is asserted unconditionally: it needs nothing but this repository, and
     * leaving it behind the manifest assumption would mean this repo's own CI pins nothing (the
     * portal workspace is not cloned there, and the boot smoke hands itself the port through an env
     * var, so it passes whatever the file says). Only the COMPARISON with the manifest may skip.
     */
    @Test
    @DisplayName("the manifest probes the port the shipped properties actually put the actuator on")
    void the_manifest_probes_the_port_the_actuator_listens_on() throws Exception {
        java.util.Properties deployed = new java.util.Properties();
        try (java.io.InputStream file = java.nio.file.Files.newInputStream(DEPLOYED_PROPERTIES)) {
            deployed.load(file);
        }
        String configured = deployed.getProperty("management.server.port");
        org.junit.jupiter.api.Assertions.assertNotNull(configured,
                "the actuator is expected on a connector of its own — a burst of uploads must not"
                        + " be able to time out the probe that judges it. Without this property it"
                        + " shares the request port, and every k8s probe asks a port that answers"
                        + " something else entirely.");

        java.nio.file.Path manifest = java.nio.file.Path.of("../../k8s/base/memes.yaml");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(manifest),
                "the portal workspace is not checked out around this repo — the one layout in which"
                        + " a manifest guard may legitimately skip");

        org.junit.jupiter.api.Assertions.assertEquals(configured, managementContainerPort(manifest),
                "the container port named 'management' in " + manifest + " must be the port the"
                        + " service actually opens (" + configured + "), or all three probes in that"
                        + " manifest ask a closed port and the pod never goes Ready — while every"
                        + " suite and both CI gates stay green");
    }

    /** The {@code containerPort} of the port entry named {@code management}, as a string. */
    private static String managementContainerPort(java.nio.file.Path manifest) throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(manifest);
        for (int line = 0; line < lines.size(); line++) {
            if (!lines.get(line).trim().equals("- name: management")) {
                continue;
            }
            for (int next = line + 1; next < lines.size(); next++) {
                String candidate = lines.get(next).trim();
                if (candidate.startsWith("containerPort:")) {
                    return candidate.substring("containerPort:".length()).trim();
                }
                if (candidate.startsWith("- name:")) {
                    break;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("/actuator/health/readiness answers, and the listener lamp is in what it answers")
    void the_readiness_url_from_the_manifest_serves_the_lamp() throws Exception {
        http.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.kafkaListeners").exists());
    }

    @Test
    @DisplayName("/actuator/health/liveness answers, and the lamp is deliberately NOT in it")
    void the_liveness_url_from_the_manifest_excludes_the_lamp() throws Exception {
        http.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.kafkaListeners").doesNotExist());
    }

    @Test
    @DisplayName("and the bare /actuator/health the compose stack probes still answers too")
    void the_url_the_compose_healthcheck_uses_answers() throws Exception {
        http.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
