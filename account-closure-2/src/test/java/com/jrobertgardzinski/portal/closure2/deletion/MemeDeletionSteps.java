package com.jrobertgardzinski.portal.closure2.deletion;

import com.jrobertgardzinski.memes.system.DeleteMeme;
import com.jrobertgardzinski.portal.closure2.world.CommentAnnouncements;
import com.jrobertgardzinski.portal.closure2.world.ContentIds;
import com.jrobertgardzinski.portal.closure2.world.Portal;
import io.cucumber.java.en.And;
import io.cucumber.java.en.But;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The steps of {@code ../specs-2/deleting-a-meme.feature}.
 *
 * <p>Same two rules as the closure's steps: one step is one piece of work OR one assertion, and an
 * assertion reads a fake directly. The thread is read through {@code allUnder}, which sees a row
 * whatever its state, because a read that hid anything would call a thread gone while its rows were
 * still there.
 *
 * <p>The three parts hear about each other here in three separate steps, and the second and third
 * of them could not exist without the first reporting its own work: only the part holding the
 * thread knows which comments hung under that meme, so nothing downstream can work it out from the
 * meme's name alone.
 */
public class MemeDeletionSteps {

    private static final String THE_MEME = ContentIds.memeOf("alice", 1);
    private static final String THE_SECOND_MEME = ContentIds.memeOf("alice", 2);

    private final Portal portal = new Portal();

    private final List<String> thread = new java.util.ArrayList<>();
    private DeleteMeme.Result lastTakeDown;

    // ---------------------------------------------------------------- what there is to begin with

    @Given("alice posted a meme")
    public void alice_posted_a_meme() {
        portal.memes().rows().posted(THE_MEME, portal.arrived("alice"));
    }

    @Given("alice posted a second meme nobody commented under")
    public void alice_posted_a_second_meme_nobody_commented_under() {
        portal.memes().rows().posted(THE_SECOND_MEME, portal.arrived("alice"));
    }

    @And("{int} people commented under it")
    public void people_commented_under_it(int howMany) {
        for (int i = 1; i <= howMany; i++) {
            String reader = "reader" + i;
            String commentId = ContentIds.commentOf(reader, 1);
            portal.comments().wroteUnder(THE_MEME, commentId, portal.arrived(reader));
            thread.add(commentId);
        }
    }

    @And("bob saved it")
    public void bob_saved_it() {
        portal.collections().savedPointingAt(portal.arrived("bob"), "meme", THE_MEME);
    }

    @And("carol saved one of the comments under it")
    public void carol_saved_one_of_the_comments_under_it() {
        portal.collections().savedPointingAt(portal.arrived("carol"), "comment", thread.getFirst());
    }

    // ------------------------------------------------------------------------- moving the chain on

    @When("alice takes her meme down")
    public void alice_takes_her_meme_down() {
        lastTakeDown = portal.memes().takeDown(THE_MEME);
    }

    @When("alice takes her second meme down")
    public void alice_takes_her_second_meme_down() {
        lastTakeDown = portal.memes().takeDown(THE_SECOND_MEME);
    }

    @When("alice takes down a meme nobody has")
    public void alice_takes_down_a_meme_nobody_has() {
        lastTakeDown = portal.memes().takeDown("a-meme-nobody-has");
    }

    @When("comments has heard that the meme is gone")
    public void comments_has_heard_that_the_meme_is_gone() {
        portal.memeAnnouncements().newFor("comments").forEach(portal.comments()::dropThreadUnder);
    }

    @When("comments has heard that the second meme is gone")
    public void comments_has_heard_that_the_second_meme_is_gone() {
        comments_has_heard_that_the_meme_is_gone();
    }

    @When("comments hears the same thing again")
    public void comments_hears_the_same_thing_again() {
        portal.memeAnnouncements().againFor("comments").forEach(portal.comments()::dropThreadUnder);
    }

    @When("collections has heard that the meme is gone")
    public void collections_has_heard_that_the_meme_is_gone() {
        portal.collections().forget("meme", portal.memeAnnouncements().newFor("collections"));
    }

    @When("collections has heard which comments went")
    public void collections_has_heard_which_comments_went() {
        for (CommentAnnouncements.Announcement said : portal.commentAnnouncements().newFor("collections")) {
            portal.collections().forget("comment", said.commentIds());
        }
    }

    // ------------------------------------------------------------------------------- the assertions

    @Then("alice's meme is gone")
    public void alices_meme_is_gone() {
        assertFalse(portal.memes().rows().isMarked(THE_MEME), "the meme is out of sight, not gone");
        assertFalse(portal.memes().rows().allIds().contains(THE_MEME), "the meme is still held");
    }

    @But("the thread under it is still there")
    public void the_thread_under_it_is_still_there() {
        assertEquals(thread.size(), portal.comments().rows().allUnder(THE_MEME).size(),
                "the thread went before the part holding it had heard anything");
    }

    @And("the thread under her meme is still there")
    public void the_thread_under_her_meme_is_still_there() {
        the_thread_under_it_is_still_there();
    }

    @Then("the thread under it is gone")
    public void the_thread_under_it_is_gone() {
        assertTrue(portal.comments().rows().allUnder(THE_MEME).isEmpty(),
                "a comment still hangs under a meme that is not there");
    }

    @And("bob still has it saved")
    public void bob_still_has_it_saved() {
        assertTrue(portal.collections().holds(portal.idOf("bob"), "meme", THE_MEME),
                "bob lost his pointer before the part holding it had heard anything");
    }

    @Then("bob's reference to it is gone")
    public void bobs_reference_to_it_is_gone() {
        assertFalse(portal.collections().holds(portal.idOf("bob"), "meme", THE_MEME),
                "bob is still pointing at a meme that is not there");
    }

    @Then("carol's reference is gone")
    public void carols_reference_is_gone() {
        assertFalse(portal.collections().holds(portal.idOf("carol"), "comment", thread.getFirst()),
                "carol is still pointing at a comment that is not there");
    }

    @And("the comments of that meme were named once, not twice")
    public void the_comments_of_that_meme_were_named_once() {
        assertEquals(1, portal.commentAnnouncements().about(THE_MEME).size(),
                "the same thread was reported gone more than once");
    }

    @Then("nothing was said about the comments of the second meme")
    public void nothing_was_said_about_the_comments_of_the_second_meme() {
        assertTrue(portal.commentAnnouncements().about(THE_SECOND_MEME).isEmpty(),
                "a meme with nothing under it was reported as having lost a thread");
    }

    @Then("alice is told there is no such meme")
    public void alice_is_told_there_is_no_such_meme() {
        assertEquals(DeleteMeme.Status.NO_SUCH_MEME, lastTakeDown.status(),
                "she was told something else about a meme nobody has");
    }
}
