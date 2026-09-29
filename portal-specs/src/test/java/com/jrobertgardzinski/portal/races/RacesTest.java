package com.jrobertgardzinski.portal.races;

import com.jrobertgardzinski.portal.closure.ClosureInOneProcess;
import com.jrobertgardzinski.portal.world.UnitsOfWork;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every seed under every schedule its transport allows, and holds the answer to a file.
 *
 * <p>Two assertions per seed, and they are different in kind. The first is a LAW: something that
 * must be true after every step of every schedule, and a law that breaks is a defect with a
 * schedule attached. The second is a diff: the set of distinct end states, approved and checked
 * in, so that a change which lets a seed finish in a way it could not finish before is something
 * somebody has to look at and write a sentence about.
 *
 * <p>The approved files live in {@code ../specs/races}, beside the feature files, because they are
 * the same kind of thing: a statement about the portal as a whole that no single service could
 * make. Re-approve with {@code -Draces.approve=true} — and then read the diff before committing
 * it, because that diff is the whole point.
 */
@Epic("Architecture")
@Feature("Every schedule the transport allows")
@Tag("races")
class RacesTest {

    // Tagged, and NOT excluded by default. It costs about seventy seconds, nearly all of it the
    // one seed that lets the clock reach a timeout four times, and that seed is the only place
    // anything has ever asked what happens when a part's answer and the portal's capitulation are
    // on the wire at the same moment. The tag is there so a tight loop can say
    // -Dgroups='!races'; a suite that skips it by default is a suite nobody runs.

    private static final Path APPROVED = Path.of("..", "specs", "races");

    private static final Path WORKING = Path.of("target", "races");

    @TestFactory
    Stream<DynamicTest> everySchedule() {
        return Seeds.ALL.stream().map(seed -> DynamicTest.dynamicTest(seed.name(), () -> {
            Explorer.Bounds bounds = Explorer.Bounds.ofDefault()
                    .withDuplicates(seed == Seeds.AT_LEAST_ONCE || seed == Seeds.A_CASCADE_TWICE ? 1 : 0)
                    .withFailures(seed.failures());
            Explorer.Report report = Explorer.explore(seed, bounds);

            Files.createDirectories(WORKING);
            Files.writeString(WORKING.resolve(seed.name() + ".detail"), report.detail());

            assertEquals(List.of(), report.violations().stream()
                            .map(broken -> broken.law() + " — " + broken.sentence()).toList(),
                    "a law broke; the schedule that breaks it is in "
                            + WORKING.resolve(seed.name() + ".detail"));
            assertTrue(report.complete(),
                    "the search hit its bounds, so the end states below are not the whole set");

            Path approved = APPROVED.resolve(seed.name() + ".outcomes");
            String now = report.approved();
            if (Boolean.getBoolean("races.approve") || !Files.exists(approved)) {
                Files.createDirectories(APPROVED);
                Files.writeString(approved, now);
                return;
            }
            assertEquals(Files.readString(approved), now,
                    "this seed now finishes in a different set of states than the approved file "
                            + "records. Read the difference, decide whether it is all right, and "
                            + "re-approve with -Draces.approve=true");
        }));
    }

    /**
     * The model the failure axis rests on: a consumer whose transaction did not commit did not move
     * its offset either, so the broker still owes it that record. Written down as a test because it
     * is a CHOICE — the same one the silenced part forced on 28.09.2026, one level down — and a
     * layer that modelled a failed delivery as a lost record would be inventing a failure Kafka
     * cannot have.
     */
    @Test
    @DisplayName("a delivery whose transaction rolls back has consumed nothing at all")
    void a_failed_delivery_is_not_a_consumed_one() {
        Seed.Started started = Seeds.THREE_PARTS.start();
        ClosureInOneProcess portal = started.portal();
        UnitsOfWork transactions = portal.world().unitsOfWork();
        String before = portal.world().fingerprint();

        Wire.Step command = portal.wire().ready(ClosureInOneProcess.TRANSACTIONAL).get(0);
        transactions.theNextOne(UnitsOfWork.Ending.ROLLS_BACK);
        portal.wire().run(command);

        assertEquals(1, transactions.rolledBack(), "nothing failed, so this proves nothing");
        assertEquals(before, portal.world().fingerprint(), "the mark survived its own rollback");
        assertEquals(List.of(), List.copyOf(portal.confirmations().keySet()),
                "a word got out of a transaction that rolled back");

        portal.wire().unconsumed(command);
        portal.everyPartAnswers();
        portal.cascadeReachesEveryPart();

        assertEquals(List.of("PORTAL_CONTENT_PURGED"), portal.verdicts(),
                "the record came back and the closure still did not finish");
    }

    /**
     * That the laws can SEE the failure the outbox exists to prevent — the layer's own mutation
     * test, and the reason {@code SENDS_AND_THEN_ROLLS_BACK} exists while nothing offers it to a
     * search. A part says it reserved a meme, the transaction that hid it rolls back, and the saga
     * walks on to an erasure with nothing to erase: the portal tells identity the content is purged
     * while the content is still there.
     *
     * <p>Without this, "every law held on every schedule" would be a sentence about eleven seeds
     * and no evidence that a law could have broken at all.
     */
    @Test
    @DisplayName("the laws see a word that left a transaction which then rolled back")
    void the_outbox_is_load_bearing() {
        Seed.Started started = Seeds.THREE_PARTS.start();
        ClosureInOneProcess portal = started.portal();

        portal.world().unitsOfWork().theNextOne(UnitsOfWork.Ending.SENDS_AND_THEN_ROLLS_BACK);
        portal.everyPartAnswers();
        portal.cascadeReachesEveryPart();

        List<String> broken = Invariant.ALL.stream()
                .filter(law -> law.broken(portal, started.memory()).isPresent())
                .map(Invariant::name)
                .toList();

        assertTrue(broken.contains("purged means the portal holds nothing of them"),
                "the portal announced a purge over rows a rolled-back transaction never hid, and "
                        + "not one law noticed: " + broken);
        assertFalse(portal.verdicts().isEmpty(), "no verdict went out, so nothing was promised");
    }
}
