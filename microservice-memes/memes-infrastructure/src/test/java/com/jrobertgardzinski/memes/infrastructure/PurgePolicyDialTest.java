package com.jrobertgardzinski.memes.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jrobertgardzinski.memes.domain.PurgePolicyOverride;
import com.jrobertgardzinski.purge.PurgeRule;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator's dial, on the two axes nobody was watching: WHO moved it, and whether a purge can
 * catch it half-moved.
 *
 * <p>The dial decides what happens to a leaver's uploads when their client states no preference of
 * its own, so both questions are about other people's content — which is why setting it is audited
 * in the first place.
 */
@Epic("Infrastructure")
@Feature("Purge policy dial")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class PurgePolicyDialTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PurgePolicyOverride dial;

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    @BeforeEach
    void tapTheLog() {
        logLines.start();
        dialLogger().addAppender(logLines);
    }

    @AfterEach
    void untapTheLog() {
        dialLogger().detachAppender(logLines);
        logLines.stop();
    }

    private static Logger dialLogger() {
        return (Logger) LoggerFactory.getLogger(JdbcPurgePolicyOverride.class);
    }

    @Test
    @DisplayName("clearing the dial records who cleared it, and what it said")
    void the_clear_names_its_admin() throws Exception {
        dial.set(PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:5"), "first-admin@example.com");

        mockMvc.perform(delete("/admin/purge-policy")
                        .header("Authorization", "Bearer " + TestAuthConfig.ADMIN_TOKEN))
                .andExpect(status().isOk());

        // the settings row IS the audit record and the clear deletes it, so the act has to be
        // recorded before it happens or not at all — this used to be the one mutating route of the
        // three that recorded nobody
        String recorded = logLines.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("cleared")).findFirst()
                .orElse("nothing was recorded at all");
        assertTrue(recorded.contains(TestAuthConfig.ADMIN_USER),
                "the clear must name the admin who did it — recorded: " + recorded);
        assertTrue(recorded.contains("KEEP_POPULAR_ANONYMIZED:5"),
                "and what it took away, which no other record survives — recorded: " + recorded);
    }

    @Test
    @DisplayName("a purge reading the dial while an admin saves it never finds it unset")
    void saving_the_dial_has_no_gap() throws Exception {
        // The dial is only ever read by PurgeUserContent, on the Kafka listener thread, as
        // "the rule the command carried, else this override, else the deployment default" — so a
        // read that lands in a gap does not fail, it silently applies the deployment default to
        // content the operator's rule said to keep, past the saga's irreversible pivot.
        dial.set(PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:5"), "admin@example.com");
        AtomicBoolean saving = new AtomicBoolean(true);
        AtomicInteger readsWithNoRule = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();

        Thread purgesReading = new Thread(() -> {
            while (saving.get()) {
                Optional<PurgeRule> seen = dial.current();
                reads.incrementAndGet();
                if (seen.isEmpty()) {
                    readsWithNoRule.incrementAndGet();
                }
            }
        });
        purgesReading.start();
        for (int i = 0; i < 300; i++) {
            dial.set(PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:" + (i % 9 + 1)), "admin@example.com");
        }
        saving.set(false);
        purgesReading.join();

        assertTrue(reads.get() > 0, "the reader really did read the dial while it was being saved");
        assertEquals(0, readsWithNoRule.get(),
                "an admin saving the dial must never make it read as unset — it did on "
                        + readsWithNoRule.get() + " of " + reads.get() + " reads");
    }
}
