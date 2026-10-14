package com.jrobertgardzinski.portal.closure2.protocol;

import com.jrobertgardzinski.portal.closure2.world.ClosureProtocol;
import com.jrobertgardzinski.portal.closure2.world.ClosureProtocol.Outcome;
import com.jrobertgardzinski.portal.closure2.world.ClosureProtocol.Report;
import com.jrobertgardzinski.portal.closure2.world.ClosureProtocol.State;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@link ClosureProtocol} to the state diagram in {@code closure-protocol.adoc}, which it
 * reads rather than copies. Three claims make the picture and the machine the same thing: a report
 * with an arrow out of a state is not refused there and lands where an arrow points; a report with no
 * arrow is refused and moves nothing; every arrow drawn is walked by some test here. The counting —
 * which report is "the last" — is in the named tests, as the page says.
 */
class ClosureProtocolTest {

    /** What moves the closure: one of the three reports, or giving up on it. */
    enum Trigger {
        HIDDEN, DESTROYED, RESTORED, GIVEN_UP;

        static Trigger of(String label) {
            return valueOf(label.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        }
    }

    record Arrow(State from, Trigger on, State to) {
        @Override
        public String toString() {
            return from + " --" + on + "--> " + to;
        }
    }

    private static final Diagram DIAGRAM = Diagram.read("/closure-protocol.adoc");
    private static final Set<Arrow> WALKED = new HashSet<>();

    // ----------------------------------------------------------------------- the picture as a whole

    @Test
    void the_diagram_names_every_state_and_no_other() {
        assertEquals(EnumSet.allOf(State.class), DIAGRAM.states);
    }

    @Test
    void a_closure_opens_where_the_diagram_starts() {
        assertEquals(DIAGRAM.initial, new Walk().state());
    }

    @Test
    void a_state_with_no_arrow_out_is_an_end() {
        Set<State> dead = EnumSet.allOf(State.class);
        DIAGRAM.arrows.forEach(arrow -> dead.remove(arrow.from()));
        assertEquals(DIAGRAM.terminal, dead, "a state drawn as an end still has a way out, or the reverse");
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("everyCell")
    void a_state_takes_what_the_diagram_draws_and_refuses_the_rest(State state, Trigger trigger) {
        Walk walk = Walk.to(state);
        Outcome outcome = walk.fire(trigger, "memes");
        if (DIAGRAM.targets(state, trigger).isEmpty()) {
            assertEquals(Outcome.REFUSED, outcome, state + " takes " + trigger + ", which the diagram does not draw");
        } else {
            assertNotEquals(Outcome.REFUSED, outcome, state + " refuses " + trigger + ", which the diagram draws");
        }
    }

    static Stream<Arguments> everyCell() {
        return Stream.of(State.values())
                .flatMap(state -> Stream.of(Trigger.values()).map(trigger -> Arguments.of(state, trigger)));
    }

    @AfterAll
    static void every_arrow_drawn_was_walked() {
        Set<Arrow> drawn = new LinkedHashSet<>(DIAGRAM.arrows);
        drawn.removeAll(WALKED);
        assertTrue(drawn.isEmpty(), "drawn but never walked: " + drawn);
    }

    // ------------------------------------------------------------ what the picture does not carry

    @Test
    void the_account_is_asked_to_go_at_the_last_hidden_and_not_before() {
        Walk walk = new Walk();
        walk.fire(Trigger.HIDDEN, "memes");
        walk.fire(Trigger.HIDDEN, "comments");
        assertFalse(walk.account.askedToGo, "asked to go before every part had hidden its share");
        assertEquals(State.HIDING, walk.state());
        walk.fire(Trigger.HIDDEN, "collections");
        assertTrue(walk.account.gone);
        assertEquals(State.DESTROYING, walk.state());
    }

    @Test
    void an_account_that_refuses_turns_the_closure_back_and_is_unlocked() {
        Walk walk = new Walk();
        walk.account.refuses = true;
        ClosureProtocol.PARTS.forEach(part -> walk.fire(Trigger.HIDDEN, part));
        assertTrue(walk.account.askedToGo);
        assertFalse(walk.account.gone);
        assertTrue(walk.account.unlocked, "turned back and left the account locked");
        assertEquals(State.RESTORING, walk.state());
    }

    @Test
    void the_closure_is_done_at_the_last_destroyed() {
        Walk walk = Walk.to(State.DESTROYING);
        walk.fire(Trigger.DESTROYED, "memes");
        walk.fire(Trigger.DESTROYED, "comments");
        assertEquals(State.DESTROYING, walk.state());
        walk.fire(Trigger.DESTROYED, "collections");
        assertEquals(State.DONE, walk.state());
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("eachReportInItsState")
    void the_same_report_from_the_same_part_is_nothing_new(State state, Trigger report) {
        Walk walk = Walk.to(state);
        assertEquals(Outcome.ACCEPTED, walk.fire(report, "memes"));
        assertEquals(Outcome.NOTHING_NEW, walk.fire(report, "memes"));
        assertEquals(state, walk.state());
    }

    static Stream<Arguments> eachReportInItsState() {
        return Stream.of(
                Arguments.of(State.HIDING, Trigger.HIDDEN),
                Arguments.of(State.DESTROYING, Trigger.DESTROYED),
                Arguments.of(State.RESTORING, Trigger.RESTORED));
    }

    @Test
    void giving_up_while_hiding_unlocks_the_account_and_waits_for_what_hid() {
        Walk walk = new Walk();
        walk.fire(Trigger.HIDDEN, "memes");
        assertEquals(Outcome.ACCEPTED, walk.fire(Trigger.GIVEN_UP, null));
        assertFalse(walk.account.askedToGo);
        assertTrue(walk.account.unlocked);
        assertEquals(State.RESTORING, walk.state());
    }

    @Test
    void giving_up_before_anything_hid_is_withdrawn_at_once() {
        Walk walk = new Walk();
        assertEquals(Outcome.ACCEPTED, walk.fire(Trigger.GIVEN_UP, null));
        assertTrue(walk.account.unlocked);
        assertEquals(State.WITHDRAWN, walk.state());
    }

    @Test
    void giving_up_a_closure_that_already_turned_back_is_nothing_new() {
        Walk walk = Walk.to(State.RESTORING);
        assertEquals(Outcome.NOTHING_NEW, walk.fire(Trigger.GIVEN_UP, null));
        assertEquals(State.RESTORING, walk.state());
    }

    @Test
    void only_a_part_that_hid_has_anything_to_restore() {
        Walk walk = new Walk();
        walk.fire(Trigger.HIDDEN, "memes");
        walk.fire(Trigger.GIVEN_UP, null);
        assertEquals(Outcome.REFUSED, walk.fire(Trigger.RESTORED, "comments"), "a part that hid nothing restored something");
        assertEquals(State.RESTORING, walk.state());
        assertEquals(Outcome.ACCEPTED, walk.fire(Trigger.RESTORED, "memes"));
        assertEquals(State.WITHDRAWN, walk.state());
    }

    // ------------------------------------------------------------------------------- the machinery

    /** An account row that remembers whether it was asked to go, whether it went, and whether it was unlocked. */
    static final class RecordingAccount implements ClosureProtocol.Account {
        boolean refuses;
        boolean askedToGo;
        boolean gone;
        boolean unlocked;

        @Override
        public void go() {
            askedToGo = true;
            if (refuses) {
                throw new IllegalStateException("the user row refuses to be deleted");
            }
            gone = true;
        }

        @Override
        public void unlock() {
            unlocked = true;
        }
    }

    /** One closure, driven through the diagram: every move it makes is checked against an arrow and remembered. */
    static final class Walk {
        final RecordingAccount account = new RecordingAccount();
        private final ClosureProtocol protocol = new ClosureProtocol(account);

        /** The canonical road to a state: every part reports, and the account goes or refuses. */
        static Walk to(State state) {
            Walk walk = new Walk();
            switch (state) {
                case HIDING -> { }
                case DESTROYING -> walk.everyPart(Trigger.HIDDEN);
                case RESTORING -> {
                    walk.account.refuses = true;
                    walk.everyPart(Trigger.HIDDEN);
                }
                case DONE -> {
                    walk.everyPart(Trigger.HIDDEN);
                    walk.everyPart(Trigger.DESTROYED);
                }
                case WITHDRAWN -> {
                    walk.account.refuses = true;
                    walk.everyPart(Trigger.HIDDEN);
                    walk.everyPart(Trigger.RESTORED);
                }
            }
            assertEquals(state, walk.state(), "the canonical road did not lead there");
            return walk;
        }

        State state() {
            return protocol.state();
        }

        Outcome fire(Trigger trigger, String part) {
            State from = protocol.state();
            Outcome outcome = trigger == Trigger.GIVEN_UP
                    ? protocol.givenUp()
                    : protocol.report(Report.valueOf(trigger.name()), part);
            if (outcome == Outcome.REFUSED) {
                assertEquals(from, protocol.state(), "a refused report moved the closure");
            } else {
                Arrow arrow = new Arrow(from, trigger, protocol.state());
                assertTrue(DIAGRAM.arrows.contains(arrow), "walked an arrow the diagram does not draw: " + arrow);
                WALKED.add(arrow);
            }
            return outcome;
        }

        private void everyPart(Trigger report) {
            ClosureProtocol.PARTS.forEach(part -> fire(report, part));
        }
    }

    /** The arrows between {@code @startuml} and {@code @enduml}, and nothing else of the page. */
    static final class Diagram {
        private static final Pattern ARROW = Pattern.compile("^\\s*(\\S+)\\s+-+>\\s+(\\S+)\\s*(?::\\s*(.*))?$");

        final Set<Arrow> arrows = new LinkedHashSet<>();
        final Set<State> states = EnumSet.noneOf(State.class);
        final Set<State> terminal = EnumSet.noneOf(State.class);
        State initial;

        static Diagram read(String resource) {
            String page;
            try (InputStream in = ClosureProtocolTest.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(resource + " is not on the test classpath");
                }
                page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            int start = page.indexOf("@startuml");
            int end = page.indexOf("@enduml");
            if (start < 0 || end < start) {
                throw new IllegalStateException(resource + " has no @startuml ... @enduml block");
            }
            Diagram diagram = new Diagram();
            page.substring(start, end).lines().forEach(diagram::take);
            return diagram;
        }

        private void take(String line) {
            Matcher m = ARROW.matcher(line);
            if (!m.matches()) {
                return;
            }
            String from = m.group(1);
            String to = m.group(2);
            if (from.equals("[*]")) {
                initial = State.valueOf(to);
                states.add(initial);
            } else if (to.equals("[*]")) {
                terminal.add(State.valueOf(from));
                states.add(State.valueOf(from));
            } else {
                // the label is the trigger, then optionally "\n(" and the words the test does not read
                String label = m.group(3).split("\\\\n|\\(")[0];
                arrows.add(new Arrow(State.valueOf(from), Trigger.of(label), State.valueOf(to)));
                states.add(State.valueOf(from));
                states.add(State.valueOf(to));
            }
        }

        Set<State> targets(State from, Trigger on) {
            Set<State> targets = EnumSet.noneOf(State.class);
            for (Arrow arrow : arrows) {
                if (arrow.from() == from && arrow.on() == on) {
                    targets.add(arrow.to());
                }
            }
            return targets;
        }
    }
}
