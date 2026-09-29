package com.jrobertgardzinski.portal.races;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.portal.closure.ClosureInOneProcess;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A world, the thing that starts a protocol over it, and whatever else somebody might do while it
 * runs — everything an {@link Explorer} needs to build the same situation again and again.
 *
 * <p>A seed is small on purpose. Two memes and a handful of comments is the scale of the
 * {@code Background} in {@code ../specs}, and the number of schedules grows with the number of
 * records in flight, not with the number of rows. A bigger world would cost a great deal of search
 * and say nothing a small one does not.
 *
 * <p>An INTRUSION is somebody outside both protocols acting while they run — a stranger taking
 * their own meme down, a silenced part coming back, security asking a second time. It rides its
 * own lane, so it is ready from the first step to the last and the explorer may put it anywhere.
 * That is what a request from outside IS to a saga: unordered against everything.
 */
public final class Seed {

    /**
     * What the world held before anything happened — what "gone", "new" and "given back" are
     * measured against. The leaver's own rows are remembered SEPARATELY, and their comments with
     * the meme each one hung under: a comment that vanished while its meme still stands is a row
     * somebody lost, and a comment that went with its meme is the trade the portal made on
     * purpose. Nothing can tell those two apart after the fact without this.
     */
    public record Memory(List<String> memeIds, List<String> commentIds, Optional<UserId> leaver,
                         List<String> theirMemes, Map<String, String> theirComments,
                         boolean othersMayTakeTheirContentDown) {
    }

    public record Started(ClosureInOneProcess portal, Memory memory) {
    }

    private static final String OUTSIDE = "portal-http";

    private final String name;
    private final String about;
    private final Consumer<ClosureInOneProcess> content;
    private final Consumer<ClosureInOneProcess> trigger;
    private final Map<String, Consumer<ClosureInOneProcess>> intrusions;
    private final Map<String, String> names;
    private final UserId leaver;
    private final int sweeps;
    private final int failures;
    private boolean othersMayTakeTheirContentDown;
    private boolean requestsAreRecords;

    private Seed(String name, String about, Consumer<ClosureInOneProcess> content,
                 Consumer<ClosureInOneProcess> trigger,
                 Map<String, Consumer<ClosureInOneProcess>> intrusions,
                 Map<String, String> names, UserId leaver, int sweeps, int failures) {
        this.name = name;
        this.about = about;
        this.content = content;
        this.trigger = trigger;
        this.intrusions = Map.copyOf(intrusions);
        this.names = Map.copyOf(names);
        this.leaver = leaver;
        this.sweeps = sweeps;
        this.failures = failures;
    }

    public static Seed named(String name, String about) {
        return new Seed(name, about, portal -> { }, portal -> { }, Map.of(), Map.of(), null, 0, 0);
    }

    /** The rows, put there before anything happens. */
    public Seed holding(Consumer<ClosureInOneProcess> content) {
        return new Seed(name, about, content, trigger, intrusions, names, leaver, sweeps, failures);
    }

    /** What sets a protocol off — announcing a closure, or an author taking a meme down. */
    public Seed startedBy(Consumer<ClosureInOneProcess> trigger) {
        return new Seed(name, about, content, trigger, intrusions, names, leaver, sweeps, failures);
    }

    /** Somebody outside both protocols, who may act at any moment at all. */
    public Seed interruptedBy(String what, Consumer<ClosureInOneProcess> action) {
        Map<String, Consumer<ClosureInOneProcess>> more = new LinkedHashMap<>(intrusions);
        more.put(what, action);
        return new Seed(name, about, content, trigger, more, names, leaver, sweeps, failures);
    }

    /**
     * How many units of work may fail during one schedule — a rollback, or a commit whose records
     * die in the outbox before they are sent. Nought for every seed that does not say otherwise,
     * because each one multiplies the tree by the number of transactional steps in it.
     */
    public Seed wherePartsMayFail(int failures) {
        return new Seed(name, about, content, trigger, intrusions, names, leaver, sweeps, failures);
    }

    /** How many times the clock may reach a purge timeout during one schedule. */
    public Seed patientFor(int sweeps) {
        return new Seed(name, about, content, trigger, intrusions, names, leaver, sweeps, failures);
    }

    /** Whose closure this is — what "the portal holds nothing of them" is asked about. */
    public Seed closing(UserId leaver) {
        return new Seed(name, about, content, trigger, intrusions, names, leaver, sweeps, failures);
    }

    /**
     * In this seat, a moderator (or the author themselves) may take the leaver's own content down
     * while the closure runs — so a row of theirs being gone under a failed verdict is not by
     * itself a compensation that lost something. The law asks the weaker question here, because
     * nothing in the world afterwards can tell a moderator's take-down from an erasure that
     * should never have happened.
     */
    public Seed whereOthersMayTakeTheirContentDown() {
        othersMayTakeTheirContentDown = true;
        return this;
    }

    /**
     * The closure request arrives as a record on its own lane, like everything else — so the
     * transaction that OPENS the case can fail, and the case can be opened at any moment relative
     * to whatever else is going on.
     *
     * <p>Opt-in, because it lets the clock tick before the request arrives: a seed with a held
     * participant then ends in one state per way of spending its patience, and none of those is a
     * decision the portal made.
     */
    public Seed whereTheRequestIsARecord() {
        requestsAreRecords = true;
        return this;
    }

    /** A name for an id, so the report reads as sentences rather than as hexadecimal. */
    public Seed calling(String id, String what) {
        Map<String, String> more = new LinkedHashMap<>(names);
        more.put(id, what);
        return new Seed(name, about, content, trigger, intrusions, more, leaver, sweeps, failures);
    }

    public Started start() {
        ClosureInOneProcess portal = new ClosureInOneProcess();
        if (requestsAreRecords) {
            portal.deliverRequestsAsRecords();
        }
        content.accept(portal);
        Memory memory = new Memory(portal.world().memeIds(), portal.world().commentIds(),
                Optional.ofNullable(leaver),
                leaver == null ? List.of()
                        : portal.memes.heldBy(leaver).stream().map(meme -> meme.id()).toList(),
                leaver == null ? Map.of()
                        : portal.comments.heldBy(leaver).stream().collect(
                                java.util.stream.Collectors.toMap(row -> row.id(),
                                        row -> row.memeId())),
                othersMayTakeTheirContentDown);
        trigger.accept(portal);
        intrusions.forEach((what, action) -> portal.wire().enqueue(
                new Wire.Lane(OUTSIDE, what, "portal"), what, () -> action.accept(portal)));
        return new Started(portal, memory);
    }

    public Names names() {
        return new Names(names);
    }

    public String name() {
        return name;
    }

    public String about() {
        return about;
    }

    public int sweeps() {
        return sweeps;
    }

    public int failures() {
        return failures;
    }
}
