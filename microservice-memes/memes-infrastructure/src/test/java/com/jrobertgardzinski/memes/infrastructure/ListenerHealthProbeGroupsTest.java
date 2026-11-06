package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHICH probe the listener lamp hangs on — a configuration pin, the same species as
 * {@link KafkaProducerClocksTest}: the decision lives in {@code application.properties}, where no code
 * can be wrong about it and nothing else would notice if it changed.
 *
 * <p>The decision: readiness, never liveness. A stopped Kafka listener cannot do this service's share
 * of an account deletion, so it must stop being handed work — but it is NOT a dead process (HTTP keeps
 * serving the gallery) and a restart would fix neither the broker nor the database that stopped it. A
 * liveness probe wired to it would crash-loop the pod through every dependency outage, which is
 * precisely the reasoning microservice-offboarding and microservice-user-collections wrote down when
 * they split {@code /alive} from {@code /health}. Getting these two lines the wrong way round is a
 * one-word mistake with a self-inflicted outage attached, and this test is what fails instead.
 *
 * <p>It also pins that the groups EXIST outside Kubernetes at all: Spring only auto-enables the
 * liveness/readiness probes when it detects a k8s (or Cloud Foundry) environment, and the portal's
 * actual home is a compose stack, where the groups would otherwise answer 404.
 */
@Epic("Infrastructure")
@Feature("Listener health")
@Story("Probe group membership")
@SpringBootTest(classes = MemesApplication.class)
class ListenerHealthProbeGroupsTest {

    /** The contributor name is the bean name in {@link SagaParticipantConfig}. */
    private static final String LAMP = "kafkaListeners";

    @Autowired
    HealthEndpointGroups groups;

    @Test
    @DisplayName("the listener lamp is a member of the readiness group")
    void the_lamp_is_in_readiness() {
        HealthEndpointGroup readiness = groups.get("readiness");

        assertNotNull(readiness, "the readiness group must exist even off Kubernetes — the compose"
                + " stack is where this service actually runs");
        assertTrue(readiness.isMember(LAMP),
                "a participant whose listener loop has stopped must stop being handed work");
        assertTrue(readiness.isMember("readinessState"),
                "and the application's own readiness state stays in the group it came from");
    }

    @Test
    @DisplayName("and it is NOT a member of the liveness group — a dead listener is not a dead process")
    void the_lamp_is_not_in_liveness() {
        HealthEndpointGroup liveness = groups.get("liveness");

        assertNotNull(liveness);
        assertFalse(liveness.isMember(LAMP),
                "restarting on this would crash-loop the pod through every broker outage without"
                        + " fixing the broker");
        assertTrue(liveness.isMember("livenessState"));
    }
}
