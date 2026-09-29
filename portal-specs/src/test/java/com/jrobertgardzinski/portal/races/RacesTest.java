package com.jrobertgardzinski.portal.races;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    .withDuplicates(seed == Seeds.AT_LEAST_ONCE || seed == Seeds.A_CASCADE_TWICE ? 1 : 0);
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
}
