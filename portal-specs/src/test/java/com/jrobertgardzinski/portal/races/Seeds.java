package com.jrobertgardzinski.portal.races;

import com.jrobertgardzinski.closure.ClosureInitiator;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.portal.world.ContentIds;
import com.jrobertgardzinski.portal.world.Identities;

import java.util.List;

/**
 * The situations worth asking the order question about — each one a place where the code says
 * something about concurrency that exactly one schedule has ever tested.
 *
 * <p>They are deliberately the same situations {@code ../specs} already describes, in the same
 * words and with the same ids. A seed that invented its own world would produce findings nobody
 * could match to a promise.
 */
public final class Seeds {

    private static final String LEAVER = "alice@example.com";
    private static final String OWNER = "bob@example.com";
    private static final String OTHER_LEAVER = "carol@example.com";
    private static final UserId ALICE = Identities.idOf(LEAVER);

    /** The meme belonging to somebody who is NOT leaving — {@code AccountClosureSteps} spells it so. */
    private static final String STRANGERS_MEME = ContentIds.of("a-strangers-meme");

    /** The leaver's own comment under that meme — the row both protocols can reach. */
    private static final String LEAVERS_COMMENT = ContentIds.of(STRANGERS_MEME + "-comment");

    private static String memeOfTheLeaver(int which) {
        return ContentIds.of(LEAVER + "-meme-" + which);
    }

    private static String strangersCommentUnder(String memeId) {
        return ContentIds.of(memeId + "-stranger-comment-1");
    }

    /** Every seed names its people and its rows the same way, so the reports read alike. */
    private static Seed about(String name, String what) {
        return Seed.named(name, what)
                .closing(ALICE)
                .calling(ALICE.toString(), "alice")
                .calling(Identities.idOf(OWNER).toString(), "bob")
                .calling(memeOfTheLeaver(1), "alice's meme")
                .calling(memeOfTheLeaver(2), "alice's second meme")
                .calling(strangersCommentUnder(memeOfTheLeaver(1)), "a stranger's comment under it")
                .calling(STRANGERS_MEME, "bob's meme")
                .calling(LEAVERS_COMMENT, "alice's comment under bob's meme")
                .calling(ContentIds.of(LEAVER + "-comment-1"), "alice's own comment")
                .calling(ContentIds.of("someones-meme-1"), "a meme alice had saved")
                .calling(ContentIds.of("someones-meme"), "somebody's meme")
                .calling(Identities.idOf("stranger-1@example.com").toString(), "a stranger")
                .calling(Identities.idOf("stranger@example.com").toString(), "a stranger")
                .calling(Identities.idOf("saver-1@example.com").toString(), "somebody who saved it")
                .calling(Identities.idOf("comment-saver-1@example.com").toString(),
                        "somebody who saved the comment");
    }

    /**
     * One closure, three parts, nothing else. The closure bus used to fan a command out to the
     * three participants in a hard-coded {@code List.of(MEMES, COMMENTS, COLLECTIONS)}; production
     * runs them in three consumer groups and orders them in no way at all.
     */
    public static final Seed THREE_PARTS = about("three-parts",
            "One closure of an account with content on all three axes and nothing else going on. "
                    + "The three participants are three consumer groups and the orchestrator is a "
                    + "fourth; nothing orders any of them against the others. The question is "
                    + "whether the portal ends up in the same place whichever way they are run.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                portal.comments.wrote(LEAVER, ALICE, 1);
                portal.favourites.saved(ALICE, 1);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null));

    /**
     * The seam, from the closure's side: the leaver's meme carries a stranger's thread and a
     * stranger's saved pointer, so erasing it starts a cascade that nobody waits for. The specs
     * drain that cascade on a step of their own, always at the end; here it may land anywhere.
     */
    public static final Seed CASCADE_AFTER_THE_PIVOT = about("cascade-after-the-pivot",
            "Alice's meme has a stranger's thread under it and two strangers' pointers at it and "
                    + "into it. Erasing it starts a cascade AFTER the pivot — and the cascade's "
                    + "two hops are keyed by the meme while the saga is keyed by the leaver, so "
                    + "nothing orders them against each other. The specs deliver that cascade on a "
                    + "step of their own, always last; this asks about every other placement.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                String meme = memeOfTheLeaver(1);
                portal.comments.wroteUnder(meme, strangersCommentUnder(meme),
                        Identities.idOf("stranger-1@example.com"));
                portal.favourites.savedPointingAt(Identities.idOf("saver-1@example.com"),
                        "meme", meme);
                portal.favourites.savedPointingAt(Identities.idOf("comment-saver-1@example.com"),
                        "comment", strangersCommentUnder(meme));
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null));

    /**
     * The seam, from the cascade's side and the one {@code account-closure.feature:155} states for
     * a single schedule: a stranger takes their OWN meme down while the closure is mid-flight, and
     * the thread that goes with it holds a comment the saga has reserved and promised.
     */
    public static final Seed BOTH_PROTOCOLS_ON_ONE_ROW = about("both-protocols-on-one-row",
            "Bob's meme with alice's comment under it, and bob takes his meme down at some point "
                    + "during her closure. The cascade's delete is blind to the reservation — "
                    + "deliberately, because it takes a whole thread — so the row can go out from "
                    + "under the saga. The feature file states ONE placement of that take-down; "
                    + "this asks about all of them.")
            .holding(portal -> {
                portal.memes.posted(STRANGERS_MEME, Identities.idOf(OWNER));
                portal.comments.wroteUnder(STRANGERS_MEME, LEAVERS_COMMENT, ALICE);
                portal.favourites.savedPointingAt(Identities.idOf("stranger@example.com"),
                        "comment", LEAVERS_COMMENT);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null))
            .interruptedBy("bob takes his own meme down",
                    portal -> portal.takeDown(STRANGERS_MEME));

    /**
     * At-least-once. The whole estate is built on it — {@code transactional-outbox}'s README says
     * the event id is pasted into the payload "so a redelivery is a recognisable duplicate" — and
     * no consumer in the portal keeps a table of what it has processed: idempotence is structural.
     * The specs test it in sequence, once, on the last MEME_DELETED only.
     */
    public static final Seed AT_LEAST_ONCE = about("at-least-once",
            "One closure with a thread and a pointer, and the broker may hand any ONE record over "
                    + "twice, anywhere — a command, a confirmation, a cascade hop or the verdict. "
                    + "No consumer in the portal keeps a record of what it has already seen, so "
                    + "every hop has to be idempotent by construction rather than by bookkeeping.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                String meme = memeOfTheLeaver(1);
                portal.comments.wroteUnder(meme, strangersCommentUnder(meme),
                        Identities.idOf("stranger-1@example.com"));
                portal.favourites.savedPointingAt(Identities.idOf("saver-1@example.com"),
                        "meme", meme);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null));

    /**
     * The sweeper, unpinned from the drain it has always been glued to, and a part that comes
     * back. Today one step winds the clock AND delivers everything, so a sweep that lands between
     * a part's answer and the orchestrator hearing it has never happened once.
     */
    public static final Seed THE_SWEEPER = about("the-sweeper",
            "The collections part never hears its command, the clock may reach the purge timeout "
                    + "at any moment, and the silent part may come back at any moment too. The "
                    + "race is a late confirmation against the capitulation: the sweeper gives up "
                    + "after the retries are spent, and the answer it gave up on may be on its way.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                portal.comments.wrote(LEAVER, ALICE, 1);
                portal.favourites.saved(ALICE, 1);
            })
            .startedBy(portal -> {
                portal.silence("collections");
                portal.securityAnnouncesClosureOf(LEAVER, ClosureInitiator.SELF.wire(), null);
            })
            .interruptedBy("the collections part hears again",
                    portal -> portal.hearsAgain("collections"))
            .patientFor(4);

    /**
     * Two requests to close the same account, racing. {@code SagaStore#start} has explicit,
     * documented handling for it — the second fact joins the running saga, and an ADMIN's case
     * that the OWNER then asks for themselves becomes the owner's with its conditions dropped —
     * and not one portal spec drives it against the real three participants.
     *
     * <p>It is the only race here that changes not the ORDER of what happens but the RULE by
     * which rows are destroyed.
     */
    public static final Seed A_SECOND_REQUEST = about("a-second-request",
            "An administrator closes the account choosing that alice's memes be kept and "
                    + "anonymised, and alice asks for the same closure herself at some point while "
                    + "it runs. The second request joins the running case and drops its conditions "
                    + "— which changes what the participants do to the rows, mid-case, and the "
                    + "parts may already have been commanded under the old ones.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                portal.comments.wrote(LEAVER, ALICE, 1);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(LEAVER,
                    ClosureInitiator.ADMIN.wire(), "{\"memes\":\"ANONYMIZE_AUTHOR\"}"))
            .interruptedBy("alice asks for the same closure herself",
                    portal -> portal.securityAnnouncesClosureOf(
                            LEAVER, ClosureInitiator.SELF.wire(), null));

    /**
     * Both seams at once, which is the shape a real closure has: the leaver owns a meme other
     * people wrote under, AND wrote under somebody else's meme, AND that somebody takes theirs
     * down mid-flight. Every lane in the portal is busy and none of them is ordered against the
     * others.
     */
    public static final Seed THE_WHOLE_SEAM = about("the-whole-seam",
            "Alice owns a meme a stranger wrote under, and wrote under bob's meme herself, and bob "
                    + "takes his down while her closure runs. One closure, two cascades, four "
                    + "consumer groups and an intrusion — every lane the portal has, busy at once, "
                    + "and nothing ordering any of them.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                String meme = memeOfTheLeaver(1);
                portal.comments.wroteUnder(meme, strangersCommentUnder(meme),
                        Identities.idOf("stranger-1@example.com"));
                portal.favourites.savedPointingAt(Identities.idOf("saver-1@example.com"),
                        "meme", meme);
                portal.memes.posted(STRANGERS_MEME, Identities.idOf(OWNER));
                portal.comments.wroteUnder(STRANGERS_MEME, LEAVERS_COMMENT, ALICE);
                portal.favourites.savedPointingAt(Identities.idOf("stranger@example.com"),
                        "comment", LEAVERS_COMMENT);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null))
            .interruptedBy("bob takes his own meme down",
                    portal -> portal.takeDown(STRANGERS_MEME));

    /**
     * The rule that reads the community's verdict, under every order. One of alice's two memes is
     * popular enough to be kept and anonymised and the other is not, so exactly one cascade should
     * ever start — and the thread under the meme that STAYS should never be told to go.
     *
     * <p>The rule itself had no scenario at all until 28.09.2026, because a mocked vote store
     * answers zero for ever. Having got it a scenario, this asks whether the scenario's answer is
     * the only one the orders allow.
     */
    public static final Seed A_POPULARITY_CONDITION = about("a-popularity-condition",
            "An administrator closes alice's account keeping any meme with two upvotes. One of her "
                    + "two memes has them and one does not, and each has a stranger's thread under "
                    + "it. One cascade should start, for the meme that goes — and the thread under "
                    + "the meme that stays should be there whichever way the records are ordered.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 2);
                for (int which = 1; which <= 2; which++) {
                    String meme = memeOfTheLeaver(which);
                    portal.comments.wroteUnder(meme, strangersCommentUnder(meme),
                            Identities.idOf("stranger-1@example.com"));
                    portal.favourites.savedPointingAt(Identities.idOf("saver-1@example.com"),
                            "meme", meme);
                }
                portal.world().memeVotes.cast(memeOfTheLeaver(1), "fan-1@example.com",
                        VoteDirection.UP);
                portal.world().memeVotes.cast(memeOfTheLeaver(1), "fan-2@example.com",
                        VoteDirection.UP);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(LEAVER,
                    ClosureInitiator.ADMIN.wire(),
                    "{\"memes\":\"KEEP_POPULAR_ANONYMIZED:2\"}"));

    /**
     * The cascade on its own, with no saga anywhere — and a duplicate. This is the promise
     * {@code meme-deletion.feature} already makes ("the same deletion twice changes nothing and
     * announces nothing"), asked of every order rather than of the one the loop ran.
     */
    public static final Seed A_CASCADE_TWICE = about("a-cascade-twice",
            "Bob takes his own meme down and the broker hands one of the resulting records over "
                    + "twice. No saga is running; this is the choreography alone, with nobody "
                    + "orchestrating and nobody waiting, asked whether a duplicate can change "
                    + "anything at all.")
            .holding(portal -> {
                portal.memes.posted(STRANGERS_MEME, Identities.idOf(OWNER));
                portal.comments.wroteUnder(STRANGERS_MEME, LEAVERS_COMMENT, ALICE);
                portal.favourites.savedPointingAt(Identities.idOf("stranger@example.com"),
                        "comment", LEAVERS_COMMENT);
                portal.favourites.savedPointingAt(Identities.idOf("saver-1@example.com"),
                        "meme", STRANGERS_MEME);
            })
            .startedBy(portal -> portal.takeDown(STRANGERS_MEME));

    /**
     * TWO people leaving at once, which is the race no spec in the estate has ever staged. The
     * two sagas are keyed by their two leavers, so they are on different partitions and nothing
     * orders them; and they reach the same rows, because one of them wrote under the other's meme.
     *
     * <p>What makes it different from a stranger's take-down is that the row the cascade takes was
     * reserved by a saga that may still have to give it BACK. A compensation can only restore what
     * it is still holding.
     */
    public static final Seed TWO_PEOPLE_LEAVING = about("two-people-leaving",
            "Alice and carol both ask to be forgotten, at the same time. Carol wrote under alice's "
                    + "meme, so erasing alice's content starts a cascade that takes a row carol's "
                    + "own closure has reserved — two sagas on two partitions, no order between "
                    + "them, and one of them destroying what the other promised to hold.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                portal.comments.wroteUnder(memeOfTheLeaver(1),
                        ContentIds.of("carols-comment"), Identities.idOf(OTHER_LEAVER));
                portal.favourites.savedPointingAt(Identities.idOf("stranger@example.com"),
                        "comment", ContentIds.of("carols-comment"));
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null))
            .interruptedBy("carol asks to be forgotten too",
                    portal -> portal.securityAnnouncesClosureOf(
                            OTHER_LEAVER, ClosureInitiator.SELF.wire(), null))
            .calling(Identities.idOf(OTHER_LEAVER).toString(), "carol")
            .calling(ContentIds.of("carols-comment"), "carol's comment under alice's meme");

    /**
     * The gap the plan named and did not measure: a closure that is given up on, after the other
     * protocol has already taken a row it reserved.
     *
     * <p>{@code account-closure.feature} states this for one schedule — "what the cascade took
     * does not come back when the portal gives up" — and calls it a decision rather than a fault.
     * A decision is worth stating once; whether it is the SAME decision on every order of the
     * same events is a different question, and this is it. The collections service is down, so the
     * case runs out of patience whatever else happens; bob may take his meme down at any moment
     * from the first step to the last.
     */
    public static final Seed GIVEN_UP_AFTER_THE_CASCADE = about("given-up-after-the-cascade",
            "The collections service is down, so alice's closure runs out of patience and is given "
                    + "back. Bob takes his own meme down at some moment during it, and alice's "
                    + "comment hangs under that meme. The compensation can only return what it is "
                    + "still holding — and the question is whether that is the same thing on every "
                    + "order of the same events.")
            .holding(portal -> {
                portal.memes.posted(STRANGERS_MEME, Identities.idOf(OWNER));
                portal.comments.wroteUnder(STRANGERS_MEME, LEAVERS_COMMENT, ALICE);
                portal.favourites.savedPointingAt(Identities.idOf("stranger@example.com"),
                        "comment", LEAVERS_COMMENT);
            })
            .startedBy(portal -> {
                portal.silence("collections");
                portal.securityAnnouncesClosureOf(LEAVER, ClosureInitiator.SELF.wire(), null);
            })
            .interruptedBy("bob takes his own meme down",
                    portal -> portal.takeDown(STRANGERS_MEME))
            .patientFor(4);

    /**
     * The failure axis, on the protocol that owes a word: one unit of work anywhere in this
     * schedule does not commit.
     *
     * <p>Both halves of what a transaction can do wrong are in the tree, at every transactional
     * step: it rolls back, or it commits and the process dies before its records leave the outbox.
     * A rollback puts the record back at the head of its lane, because a consumer that did not
     * commit did not move its offset either — the same lesson the silenced part taught on
     * 28.09.2026, one level down.
     *
     * <p>What it asks: whether the portal can be made to break a promise by failing once. The
     * world carries a thread and a pointer belonging to somebody else, so a cascade starts and its
     * comments hop — which deletes a thread and announces what it deleted in one unit of work —
     * can fail too.
     */
    public static final Seed A_UNIT_OF_WORK_THAT_FAILS = about("a-unit-of-work-that-fails",
            "Alice's closure, with one unit of work somewhere in it that does not commit: it rolls "
                    + "back, or it commits and the process dies before the outbox is sent. A "
                    + "rollback leaves the record where it was, since the offset never moved, so "
                    + "the question is not whether the work happens but whether anything the "
                    + "portal already promised can come apart from it.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                String meme = memeOfTheLeaver(1);
                portal.comments.wroteUnder(meme, strangersCommentUnder(meme),
                        Identities.idOf("stranger-1@example.com"));
                portal.comments.wrote(LEAVER, ALICE, 1);
                portal.favourites.saved(ALICE, 1);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null))
            .wherePartsMayFail(1);

    /**
     * A word that is committed and not yet said, while the clock runs out on the saga waiting for
     * it.
     *
     * <p>This is the outbox's own race, and the reason the table exists: the rows are hidden and
     * the confirmation is written in the same transaction, the process dies before the relay sends
     * it, and the orchestrator — which knows nothing of any of this — reaches its timeout and
     * commands the part again. So a re-commanded MARK, a duplicate confirmation arriving late from
     * a relay, and a saga that may already have moved on are all in flight at once.
     */
    public static final Seed A_WORD_THAT_WAITS = about("a-word-that-waits",
            "The rows are hidden and the word about them is committed to the outbox, and the "
                    + "process dies before the relay sends it. The orchestrator hears nothing, runs "
                    + "out of patience and commands the part again — and the relay may send the "
                    + "first word at any moment after that, or before it. Nothing orders the two, "
                    + "and the saga must come out of it having decided once.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                portal.comments.wrote(LEAVER, ALICE, 1);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null))
            .wherePartsMayFail(1)
            .patientFor(2);

    /**
     * The orchestrator's own transaction — the last one this layer could not fail.
     *
     * <p>The saga row it advances and the commands and verdicts it produces are one unit of work,
     * as {@code SagaOutbox} makes them. So one of its transactions here does not commit: either it
     * rolls back, leaving the confirmation it was reading at the head of its partition, or it
     * commits and the process dies before the commands and the verdict leave the outbox.
     *
     * <p>What it cannot fail is the transaction that OPENS a saga: a case is only ever opened by a
     * fact delivered outside the wire, and the reference saga store has no way to forget a row.
     */
    public static final Seed AN_ORCHESTRATOR_THAT_FAILS = about("an-orchestrator-that-fails",
            "Alice's closure, where one of the ORCHESTRATOR's units of work does not commit: the "
                    + "saga row it advanced and the records it produced go together, so a rollback "
                    + "leaves the confirmation it was reading where it was and a death after the "
                    + "commit leaves the commands in the outbox. The question is whether the case "
                    + "can be made to finish twice, or to finish and forget that it did.")
            .holding(portal -> {
                portal.memes.posted(LEAVER, ALICE, 1);
                portal.comments.wrote(LEAVER, ALICE, 1);
            })
            .startedBy(portal -> portal.securityAnnouncesClosureOf(
                    LEAVER, ClosureInitiator.SELF.wire(), null))
            .wherePartsMayFail(1);

    public static final List<Seed> ALL = List.of(THREE_PARTS, CASCADE_AFTER_THE_PIVOT,
            BOTH_PROTOCOLS_ON_ONE_ROW, AT_LEAST_ONCE, THE_SWEEPER, A_SECOND_REQUEST,
            THE_WHOLE_SEAM, A_POPULARITY_CONDITION, A_CASCADE_TWICE, TWO_PEOPLE_LEAVING,
            GIVEN_UP_AFTER_THE_CASCADE, A_UNIT_OF_WORK_THAT_FAILS, A_WORD_THAT_WAITS,
            AN_ORCHESTRATOR_THAT_FAILS);

    private Seeds() {
    }
}
