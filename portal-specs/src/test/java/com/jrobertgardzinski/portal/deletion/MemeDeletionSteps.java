package com.jrobertgardzinski.portal.deletion;

import com.jrobertgardzinski.comments.domain.Comment;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import com.jrobertgardzinski.portal.world.ContentIds;

import java.util.ArrayList;
import java.util.List;

import static com.jrobertgardzinski.portal.world.Identities.idOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Gherkin names a meme by its title and people by address, because that is how the portal's
 * people talk about them. Both are translated here, once: the portal knows a meme by id and a
 * person by {@code UserId}.
 */
public class MemeDeletionSteps {

    private final DeletionInOneProcess portal = new DeletionInOneProcess();

    /** The title Gherkin uses, and the id the portal gave it. */
    private String theMeme;
    private final List<String> commentIds = new ArrayList<>();

    @Given("{word} posted the meme {string}")
    public void postedTheMeme(String email, String title) {
        theMeme = idFor(title);
        portal.memes.posted(theMeme, idOf(email));
    }

    @Given("{int} people commented on {string}")
    public void peopleCommented(int howMany, String title) {
        for (int i = 1; i <= howMany; i++) {
            String commentId = idFor(title + "-comment-" + i);
            commentIds.add(commentId);
            portal.comments.wroteUnder(idFor(title), commentId, idOf("commenter-" + i + "@example.com"));
        }
    }

    @Given("{int} people saved {string}")
    public void peopleSavedTheMeme(int savers, String title) {
        for (int i = 1; i <= savers; i++) {
            portal.favourites.savedPointingAt(idOf("saver-" + i + "@example.com"), "meme", idFor(title));
        }
    }

    @Given("{int} people saved {string} and {int} saved comments under it")
    public void peopleSavedTheMemeAndItsComments(int savers, String title, int commentSavers) {
        peopleSavedTheMeme(savers, title);
        for (int i = 1; i <= commentSavers; i++) {
            portal.favourites.savedPointingAt(idOf("comment-saver-" + i + "@example.com"),
                    "comment", commentIds.get(i - 1));
        }
    }

    @Given("the comments part hears nothing")
    public void theCommentsPartHearsNothing() {
        portal.silence(DeletionInOneProcess.COMMENTS);
    }

    @When("{word} takes {string} down")
    public void takesTheMemeDown(String email, String title) {
        portal.takeDown(idFor(title));
    }

    @When("something announces a deletion that names no meme")
    public void somethingAnnouncesADeletionNamingNoMeme() {
        portal.announceDeletionOf("");
    }

    @When("every part of the portal hears it")
    public void everyPartHearsIt() {
        portal.everyHopAnswers();
    }

    @When("the same deletion is delivered again")
    public void theSameDeletionIsDeliveredAgain() {
        portal.redeliver();
        portal.everyHopAnswers();
    }

    @Then("{string} has no comments left")
    public void theThreadIsGone(String title) {
        assertEquals(List.of(), portal.comments.under(idFor(title)),
                "the thread outlived its meme");
    }

    @Then("nobody has {string} saved")
    public void nobodyHasTheMemeSaved(String title) {
        assertEquals(0, portal.favourites.pointingAt("meme", idFor(title)).size(),
                "a saved list still points at a meme that is gone");
    }

    @Then("nobody has a comment of {string} saved")
    public void nobodyHasItsCommentsSaved(String title) {
        for (String commentId : commentIds) {
            assertEquals(0, portal.favourites.pointingAt("comment", commentId).size(),
                    "a saved list still points at a comment that went with the meme");
        }
    }

    @Then("the {int} people who saved {string} still have it")
    public void theSaversStillHaveIt(int savers, String title) {
        assertEquals(savers, portal.favourites.pointingAt("meme", idFor(title)).size());
    }

    @Then("the {int} comments of {string} are still there")
    public void theCommentsAreStillThere(int howMany, String title) {
        List<Comment> left = portal.comments.under(idFor(title));
        assertEquals(howMany, left.size(), "the thread was dropped by a part that never heard");
    }

    @Then("the {int} saved comments of {string} are still there")
    public void theSavedCommentsAreStillThere(int howMany, String title) {
        long saved = commentIds.stream()
                .mapToLong(commentId -> portal.favourites.pointingAt("comment", commentId).size())
                .sum();
        assertEquals(howMany, saved,
                "nothing announced those comments, so nothing could have dropped their refs");
    }

    @Then("nothing was announced about the comments of {string}")
    public void nothingWasAnnouncedAboutTheComments(String title) {
        assertEquals(0, portal.commentAnnouncementsAbout(idFor(title)),
                "an empty COMMENTS_DELETED states no fact: it is noise on a shared topic");
    }

    @Then("the comments of {string} were announced once, not twice")
    public void theCommentsWereAnnouncedOnce(String title) {
        assertEquals(1, portal.commentAnnouncementsAbout(idFor(title)),
                "a redelivered deletion finds an empty thread and must announce nothing");
    }

    /** The id the portal would have minted for a title — stable, so a step can name it twice. */
    private static String idFor(String title) {
        return ContentIds.of("meme:" + title);
    }
}
