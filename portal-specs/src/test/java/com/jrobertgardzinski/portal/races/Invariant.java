package com.jrobertgardzinski.portal.races;

import com.jrobertgardzinski.portal.closure.ClosureInOneProcess;

import java.util.List;
import java.util.Optional;

/**
 * Something that has to be true of the portal after EVERY step of EVERY schedule — as opposed to
 * a scenario, which is true at the end of one.
 *
 * <p>The difference is the whole reason this interface exists. {@code ../specs} says what the
 * portal does; these say what it may never do while doing it. A scenario that asserts at the end
 * cannot tell the difference between "this never happened" and "this happened and was undone
 * before anybody looked", and the second one is what a lost pointer actually is.
 */
public interface Invariant {

    String name();

    /** Empty when it holds; otherwise the sentence that says what went wrong. */
    Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed);

    /**
     * Whether this one is only answerable once the wire is empty. A saga mid-flight is ALLOWED to
     * hold reservations; one that still holds them after the verdict went out is not.
     */
    default boolean onlyInSilence() {
        return false;
    }

    /**
     * Nobody is left pointing at something that is gone.
     *
     * <p>Only rows that were THERE when the seed was built count as gone. A favourite pointing at
     * a meme this world never held is the specs' own scenery ({@code FakeFavourites.saved} mints
     * refs to memes nobody posted), and it is not what this law is about.
     */
    Invariant NO_DANGLING_POINTER = new Invariant() {
        @Override
        public String name() {
            return "nobody is left pointing at something that is gone";
        }

        /**
         * In silence, and only there. Between a meme's deletion and the cascade reaching the
         * collections hop there IS a pointer at a row that no longer exists — that is not a
         * defect, it is what "a choreography with nobody waiting" means, and the portal's own
         * specs say so out loud ("a cascade nobody is waiting for is still on the wire"). Checked
         * after every step this law would fire on every schedule and prove nothing.
         *
         * <p>What it does prove, asked once the wire is empty, is that the window CLOSES — on
         * every order of events and not only on the one the old loop happened to run.
         */
        @Override
        public boolean onlyInSilence() {
            return true;
        }

        @Override
        public Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed) {
            for (String memeId : gone(seed.memeIds(), portal.world().memeIds())) {
                if (!portal.favourites.pointingAt("meme", memeId).isEmpty()) {
                    return Optional.of("meme " + memeId + " is gone and "
                            + portal.favourites.pointingAt("meme", memeId).size()
                            + " saved pointer(s) still name it");
                }
            }
            for (String commentId : gone(seed.commentIds(), portal.world().commentIds())) {
                if (!portal.favourites.pointingAt("comment", commentId).isEmpty()) {
                    return Optional.of("comment " + commentId + " is gone and "
                            + portal.favourites.pointingAt("comment", commentId).size()
                            + " saved pointer(s) still name it");
                }
            }
            return Optional.empty();
        }

        private List<String> gone(List<String> before, List<String> now) {
            return before.stream().filter(id -> !now.contains(id)).toList();
        }
    };

    /**
     * Nothing comes back. Neither protocol creates content — a restore takes a mark OFF a row it
     * never destroyed — so the set of ids can only ever shrink. An id that reappears would mean a
     * participant's erase and its compensation disagree about what a row is.
     */
    Invariant NOTHING_COMES_BACK = new Invariant() {
        @Override
        public String name() {
            return "no row is created by either protocol";
        }

        @Override
        public Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed) {
            for (String memeId : portal.world().memeIds()) {
                if (!seed.memeIds().contains(memeId)) {
                    return Optional.of("meme " + memeId + " was not there when this started");
                }
            }
            for (String commentId : portal.world().commentIds()) {
                if (!seed.commentIds().contains(commentId)) {
                    return Optional.of("comment " + commentId + " was not there when this started");
                }
            }
            return Optional.empty();
        }
    };

    /**
     * When the verdict has gone out and the wire is empty, nothing is left reserved.
     *
     * <p>A reservation is invisible in every public read: a row still marked after the case is
     * closed is content nobody can see and nobody will ever free, because the only two things that
     * take a mark off — ERASE and RESTORE — are both past.
     */
    Invariant NOTHING_LEFT_RESERVED = new Invariant() {
        @Override
        public String name() {
            return "the case is closed and nothing is still reserved";
        }

        @Override
        public boolean onlyInSilence() {
            return true;
        }

        @Override
        public Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed) {
            if (portal.verdicts().isEmpty()) {
                return Optional.empty();   // no case was ever closed here
            }
            String stuck = java.util.stream.Stream.of(portal.memes.rows(), portal.comments.rows(),
                            portal.favourites.rows())
                    .flatMap(List::stream)
                    .filter(row -> row.endsWith(" RESERVED"))
                    .findFirst().orElse(null);
            return stuck == null ? Optional.empty()
                    : Optional.of("the verdict " + portal.verdicts() + " went out and " + stuck);
        }
    };

    /**
     * Identity hears one verdict and one only. The saga's whole promise to the service that asked
     * is a single answer — purged, or given up on — and {@code SagaStore} calls the transition
     * that produces it the once-latch. Two DIFFERENT verdicts would mean the latch slipped.
     *
     * <p>Note what this does NOT forbid: the same verdict reaching identity twice because the
     * broker handed the record over twice. That is at-least-once, the payload carries an envelope
     * id for exactly that reason, and deduplicating it is the CONSUMER's business. What is
     * forbidden is the portal DECIDING twice.
     */
    Invariant ONE_VERDICT = new Invariant() {
        @Override
        public String name() {
            return "the portal decides once";
        }

        @Override
        public Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed) {
            List<String> distinct = portal.verdicts().stream().distinct().toList();
            return distinct.size() <= 1 ? Optional.empty()
                    : Optional.of("identity was told " + portal.verdicts());
        }
    };

    /**
     * When the portal has told identity the content is purged, the portal holds nothing of that
     * person — in silence, once every hop of everything the closure set off has run.
     *
     * <p>This is the promise the whole saga exists to keep, and it is the one thing the specs
     * assert at the end of exactly one schedule. A row of the leaver's surviving on SOME order of
     * the same events is a person who asked to be forgotten and was not.
     */
    Invariant PURGED_MEANS_NOTHING_LEFT = new Invariant() {
        @Override
        public String name() {
            return "purged means the portal holds nothing of them";
        }

        @Override
        public boolean onlyInSilence() {
            return true;
        }

        @Override
        public Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed) {
            if (seed.leaver().isEmpty() || !portal.verdicts().contains("PORTAL_CONTENT_PURGED")) {
                return Optional.empty();
            }
            var leaver = seed.leaver().get();
            int memes = portal.memes.heldBy(leaver).size();
            int comments = portal.comments.heldBy(leaver).size();
            int favourites = portal.favourites.heldBy(leaver).size();
            return memes + comments + favourites == 0 ? Optional.empty()
                    : Optional.of("the verdict said purged and the portal still holds "
                            + memes + " meme(s), " + comments + " comment(s) and "
                            + favourites + " saved reference(s) of theirs");
        }
    };

    /**
     * A thread does not outlive the meme it hangs under. Whatever destroyed the meme — the
     * author's own teardown or a closure reusing that same cascade — the conversation goes with
     * it, and it goes on every order of events, not only on the one where the cascade happens to
     * be delivered last.
     */
    Invariant NO_ORPHANED_THREAD = new Invariant() {
        @Override
        public String name() {
            return "no thread outlives the meme it hangs under";
        }

        @Override
        public boolean onlyInSilence() {
            return true;
        }

        @Override
        public Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed) {
            for (String memeId : seed.memeIds()) {
                if (portal.world().memeIds().contains(memeId)) {
                    continue;
                }
                int left = portal.comments.under(memeId).size();
                if (left > 0) {
                    return Optional.of("meme " + memeId + " is gone and " + left
                            + " comment(s) still hang under it");
                }
            }
            return Optional.empty();
        }
    };

    /**
     * When the portal has given up on a closure, the person keeps everything the portal did not
     * lose to something else.
     *
     * <p>The exception is not a loophole, it is the trade this estate already made and wrote down:
     * a comment of theirs under somebody ELSE's meme goes when that meme goes, and a compensation
     * cannot put back what the other protocol took ({@code account-closure.feature}, "what the
     * cascade took does not come back when the portal gives up"). So a row of theirs that is gone
     * is accounted for only while the meme it hung under is gone too.
     *
     * <p>Their own MEMES have no such excuse. Nothing but their own closure's erase can destroy
     * one, and under this verdict that erase never ran.
     */
    Invariant GIVEN_UP_MEANS_THEY_KEEP_IT = new Invariant() {
        @Override
        public String name() {
            return "giving up gives back everything nothing else took";
        }

        @Override
        public boolean onlyInSilence() {
            return true;
        }

        @Override
        public Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed) {
            if (seed.leaver().isEmpty() || !portal.verdicts().contains("PORTAL_PURGE_FAILED")) {
                return Optional.empty();
            }
            List<String> memesNow = portal.world().memeIds();
            for (String memeId : seed.theirMemes()) {
                if (!seed.othersMayTakeTheirContentDown() && !memesNow.contains(memeId)) {
                    return Optional.of("the portal gave up and a meme of theirs (" + memeId
                            + ") is gone anyway");
                }
            }
            List<String> commentsNow = portal.world().commentIds();
            for (var theirComment : seed.theirComments().entrySet()) {
                String commentId = theirComment.getKey();
                String under = theirComment.getValue();
                if (commentsNow.contains(commentId)) {
                    continue;
                }
                // the one excuse: the meme it hung under was REALLY there and has REALLY gone, so
                // a cascade took the whole thread. A comment hanging under a meme this world never
                // held has no cascade to blame and no excuse
                boolean aCascadeTookIt = seed.memeIds().contains(under) && !memesNow.contains(under);
                if (!aCascadeTookIt) {
                    return Optional.of("the portal gave up and a comment of theirs (" + commentId
                            + ") is gone with no cascade to account for it");
                }
            }
            return Optional.empty();
        }
    };

    /**
     * A part that has spoken is holding what it spoke about.
     *
     * <p>This is law I7 of the plan and the one that needed a unit of work able to fail before it
     * could be phrased at all. {@code AtomicClosureParticipant} exists for it: the mark and the
     * word about it are one transaction, because "hidden rows with no word owed" leaves the saga
     * waiting for a confirmation that will never come, and "a word about rows that were never
     * hidden" sends the saga on towards an erasure with nothing reserved to erase.
     *
     * <p>Asked in silence, and only while the case is still OPEN. Both things that legitimately
     * take a mark off — ERASE and RESTORE — are commanded in the same step that produces the
     * verdict, so a schedule with no verdict yet and an empty wire is one where nothing has had a
     * chance to take a reservation off a part that reserved something. Once a verdict has gone out
     * the question belongs to two other laws: the case is closed and nothing is reserved, and
     * purged means the portal holds nothing of them.
     *
     * <p>It says nothing about a part that confirmed ZERO. Confirming nothing is the truthful
     * answer of a part with nothing of that person's, and of a re-commanded MARK that finds
     * everything already reserved.
     */
    Invariant A_WORD_MEANS_ROWS = new Invariant() {
        @Override
        public String name() {
            return "a part that has spoken is holding what it spoke about";
        }

        @Override
        public boolean onlyInSilence() {
            return true;
        }

        @Override
        public Optional<String> broken(ClosureInOneProcess portal, Seed.Memory seed) {
            if (!portal.verdicts().isEmpty()) {
                return Optional.empty();
            }
            var holding = portal.reservationsBehindConfirmations();
            for (var said : portal.confirmations().entrySet()) {
                if (said.getValue() <= 0) {
                    continue;
                }
                if (holding.getOrDefault(said.getKey(), 0) == 0) {
                    return Optional.of("the " + said.getKey() + " part confirmed "
                            + said.getValue() + " reserved and is holding none of them, with no"
                            + " verdict out to account for it");
                }
            }
            return Optional.empty();
        }
    };

    List<Invariant> ALL = List.of(NO_DANGLING_POINTER, NOTHING_COMES_BACK, NOTHING_LEFT_RESERVED,
            ONE_VERDICT, PURGED_MEANS_NOTHING_LEFT, NO_ORPHANED_THREAD,
            GIVEN_UP_MEANS_THEY_KEEP_IT, A_WORD_MEANS_ROWS);
}
