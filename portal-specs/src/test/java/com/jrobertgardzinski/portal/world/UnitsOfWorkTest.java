package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.voting.VoteDirection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The one thing {@link UnitsOfWork} adds to {@code Runnable::run}: a step that does not commit.
 *
 * <p>Read against {@link Portal#fingerprint()} and not against a hand-picked row, on purpose. A
 * rollback that forgets one map is exactly the kind of defect that makes a race layer report
 * findings that are its own — and the fingerprint is what the laws next door read the portal
 * through, so anything it cannot see is not worth restoring and anything it can see must be.
 */
class UnitsOfWorkTest {

    private static final UserId LEAVER = UserId.random();

    private static final UserId STRANGER = UserId.random();

    private final Portal world = new Portal();

    private final UnitsOfWork transactions = world.unitsOfWork();

    /** Two memes, a thread under one of them, a saved reference, a ballot and the admin's dial. */
    private void aPortalWithSomethingInIt() {
        world.memes.posted("a-meme", LEAVER);
        world.memes.posted("anothers-meme", STRANGER);
        world.comments.wroteUnder("anothers-meme", "a-comment", LEAVER);
        world.favourites.savedPointingAt(STRANGER, "comment", "a-comment");
        world.memeVotes.cast("a-meme", STRANGER.toString(), VoteDirection.UP);
        world.purgePolicy.set(new PurgeRule.AnonymizeAuthor(), "an administrator");
    }

    /** Everything one step of a closure could do to that world, and then some. */
    private void everythingAStepCouldWrite() {
        world.memes.store(world.memes.activeOf(LEAVER).get(0).markForErasure(Instant.now()));
        world.comments.store(world.comments.activeOf(LEAVER).get(0).markForErasure(Instant.now()));
        world.favourites.store(world.favourites.activeOf(STRANGER).get(0)
                .markForErasure(Instant.now()));
        world.memes.deleteById("anothers-meme");
        world.memeVotes.retract("a-meme", STRANGER.toString());
        world.purgePolicy.clear("an administrator who changed their mind");
    }

    @Test
    @DisplayName("a unit of work that rolls back leaves the world it found, down to the ballots")
    void a_rollback_puts_everything_back() {
        aPortalWithSomethingInIt();
        String before = world.fingerprint();

        transactions.theNextOne(UnitsOfWork.Ending.ROLLS_BACK);
        transactions.run(this::everythingAStepCouldWrite);

        assertEquals(before, world.fingerprint());
        assertEquals(1, transactions.rolledBack());
    }

    @Test
    @DisplayName("a unit of work that commits keeps every word of it")
    void a_commit_keeps_what_it_wrote() {
        aPortalWithSomethingInIt();
        String before = world.fingerprint();

        transactions.run(this::everythingAStepCouldWrite);

        assertNotEquals(before, world.fingerprint(), "a committed step wrote nothing at all");
        assertEquals(0, transactions.rolledBack());
    }

    @Test
    @DisplayName("a mark whose row the cascade took with the thread comes back with the row")
    void a_stale_mark_is_restored_too() {
        aPortalWithSomethingInIt();
        world.comments.store(world.comments.activeOf(LEAVER).get(0).markForErasure(Instant.now()));
        String before = world.fingerprint();

        transactions.theNextOne(UnitsOfWork.Ending.ROLLS_BACK);
        transactions.run(() -> world.comments.deleteByMeme("anothers-meme"));

        assertEquals(before, world.fingerprint(),
                "the row came back without its reservation, or did not come back at all");
    }

    @Test
    @DisplayName("a step that throws rolls back, and the failure is still the caller's to see")
    void a_thrown_step_rolls_back() {
        aPortalWithSomethingInIt();
        String before = world.fingerprint();

        assertThrows(IllegalStateException.class, () -> transactions.run(() -> {
            everythingAStepCouldWrite();
            throw new IllegalStateException("the connection went away mid-erase");
        }));

        assertEquals(before, world.fingerprint());
    }

    @Test
    @DisplayName("a unit of work begun inside one already running is the same transaction")
    void nesting_joins() {
        aPortalWithSomethingInIt();
        String before = world.fingerprint();

        transactions.theNextOne(UnitsOfWork.Ending.ROLLS_BACK);
        transactions.run(() -> transactions.run(this::everythingAStepCouldWrite));

        assertEquals(before, world.fingerprint(),
                "the inner step committed on its own, so half the failure stayed");
        assertEquals(1, transactions.ran(), "the inner step opened a transaction of its own");
    }
}
