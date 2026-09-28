package com.jrobertgardzinski.portal.closure;

import com.fasterxml.jackson.databind.JsonNode;
import com.jrobertgardzinski.closure.ClosureInitiator;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.voting.VoteDirection;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jrobertgardzinski.portal.world.Identities.idOf;

import com.jrobertgardzinski.portal.world.ContentIds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The portal's promises about closing an account, driven against all four parts at once. */
public class AccountClosureSteps {

    private ClosureInOneProcess portal;

    @Before
    public void wire() {
        portal = new ClosureInOneProcess();
    }

    @Given("{word} posted {int} memes, wrote {int} comments and saved {int} favourites")
    public void aMemberWithContent(String email, int memes, int comments, int favourites) {
        portal.memes.posted(email, idOf(email), memes);
        portal.comments.wrote(email, idOf(email), comments);
        portal.favourites.saved(idOf(email), favourites);
    }

    @When("security announces that {word} asked to be forgotten")
    public void theOwnerAsks(String email) {
        portal.securityAnnouncesClosureOf(email, ClosureInitiator.SELF.wire(), null);
    }

    @When("security announces that {word} asked to be forgotten, choosing {word}={word}")
    public void theOwnerAsksStatingConditions(String email, String part, String rule) {
        portal.securityAnnouncesClosureOf(email, ClosureInitiator.SELF.wire(),
                "{\"" + part + "\":\"" + rule + "\"}");
    }

    /**
     * The community's verdict, cast by people who are NOT leaving — which is the whole point of a
     * popularity condition. The leaver's own ballots are retracted by the purge before any score
     * is read, so a score built out of them would be a score that no longer exists a line later.
     */
    @Given("{int} people upvoted {int} of {word}'s memes")
    public void theCommunityUpvotedMemes(int voters, int howManyMemes, String email) {
        for (int meme = 1; meme <= howManyMemes; meme++) {
            for (int voter = 1; voter <= voters; voter++) {
                portal.memeVotes.cast(ContentIds.of(email + "-meme-" + meme),
                        "fan-" + voter + "@example.com", VoteDirection.UP);
            }
        }
    }

    @Given("{int} people upvoted {int} of {word}'s comments")
    public void theCommunityUpvotedComments(int voters, int howManyComments, String email) {
        for (int comment = 1; comment <= howManyComments; comment++) {
            for (int voter = 1; voter <= voters; voter++) {
                portal.commentVotes.cast(ContentIds.of(email + "-comment-" + comment),
                        "fan-" + voter + "@example.com", VoteDirection.UP);
            }
        }
    }

    @When("an administrator closes {word}, choosing {word}={word} and {word}={word}")
    public void anAdministratorClosesStatingTwo(String email, String firstPart, String firstRule,
                                                String secondPart, String secondRule) {
        portal.securityAnnouncesClosureOf(email, ClosureInitiator.ADMIN.wire(),
                "{\"" + firstPart + "\":\"" + firstRule + "\",\""
                        + secondPart + "\":\"" + secondRule + "\"}");
    }

    @When("an administrator closes {word}, choosing {word}={word}")
    public void anAdministratorCloses(String email, String part, String rule) {
        portal.securityAnnouncesClosureOf(email, ClosureInitiator.ADMIN.wire(),
                "{\"" + part + "\":\"" + rule + "\"}");
    }

    @When("every part of the portal answers")
    public void everyPartAnswers() {
        portal.everyPartAnswers();
    }

    @When("every part except {word} answers")
    public void everyPartExceptOneAnswers(String silent) {
        // the command never reaches it
        portal.silence(silent);
        portal.everyPartAnswers();
    }

    /**
     * The part that had been silent comes back and the orchestrator asks once more — one retry of
     * the budget, not the capitulation. The whole point of the step is the gap before it.
     */
    @When("{word} answers after all")
    public void theSilentPartAnswersAfterAll(String participant) {
        portal.hearsAgain(participant);
        portal.waitsAndAsksAgain();
    }

    /** Nobody waits for this in production; here a scenario says when the wire is drained. */
    @When("the cascade reaches every part")
    public void theCascadeReachesEveryPart() {
        portal.cascadeReachesEveryPart();
    }

    // ---- the seam: other people's rows hanging off the leaver's memes ------------------------

    /** Comments written by people who are NOT leaving, under a meme of the one who is. */
    @Given("{int} other people commented under their {word} meme")
    public void othersCommentedUnderTheLeaversMeme(int howMany, String which) {
        String memeId = memeOfTheLeaver(which);
        for (int i = 1; i <= howMany; i++) {
            String commentId = ContentIds.of(memeId + "-stranger-comment-" + i);
            strangersComments.computeIfAbsent(memeId, any -> new ArrayList<>()).add(commentId);
            strangersRows.add(new StrangersRow("comment", memeId, commentId));
            portal.comments.wroteUnder(memeId, commentId, idOf("stranger-" + i + "@example.com"));
        }
    }

    /** Pointers in lists belonging to people who are NOT leaving — at the meme, and into its thread. */
    @Given("{int} other people saved their {word} meme, and {int} saved a comment under it")
    public void othersSavedTheLeaversMeme(int memeSavers, String which, int commentSavers) {
        String memeId = memeOfTheLeaver(which);
        for (int i = 1; i <= memeSavers; i++) {
            strangersRows.add(new StrangersRow("saved-meme", memeId, memeId));
            portal.favourites.savedPointingAt(idOf("saver-" + i + "@example.com"), "meme", memeId);
        }
        List<String> thread = strangersComments.getOrDefault(memeId, List.of());
        for (int i = 1; i <= commentSavers; i++) {
            strangersRows.add(new StrangersRow("saved-comment", memeId, thread.get(i - 1)));
            portal.favourites.savedPointingAt(idOf("comment-saver-" + i + "@example.com"),
                    "comment", thread.get(i - 1));
        }
    }

    /** Somebody else's meme, with a comment of the leaver's under it — the row two protocols want. */
    @Given("{word} posted a meme and {word} commented under it")
    public void aStrangersMemeTheLeaverCommentedUnder(String owner, String commenter) {
        portal.memes.posted(strangersMeme, idOf(owner));
        portal.comments.wroteUnder(strangersMeme, leaversComment, idOf(commenter));
    }

    /** A pointer belonging to somebody who is not leaving, into the leaver's words. */
    @Given("a stranger saved that comment")
    public void aStrangerSavedTheLeaversComment() {
        portal.favourites.savedPointingAt(idOf("stranger@example.com"), "comment", leaversComment);
    }

    @Given("{int} people upvoted that comment")
    public void theCommunityUpvotedThatComment(int voters) {
        for (int voter = 1; voter <= voters; voter++) {
            portal.commentVotes.cast(leaversComment, "fan-" + voter + "@example.com",
                    VoteDirection.UP);
        }
    }

    @When("{word} takes their meme down")
    public void theStrangerTakesTheirMemeDown(String owner) {
        portal.takeDown(strangersMeme);
    }

    @When("the portal gives up waiting")
    public void thePortalGivesUp() {
        portal.givesUpWaiting();
    }

    @Then("the portal still holds {int} memes, {int} comments and {int} favourites of theirs")
    public void thePortalStillHolds(int memes, int comments, int favourites) {
        stillHolds(theLeaver, memes, comments, favourites);
    }

    @Then("the portal holds {int} memes, {int} comments and {int} favourites of {word}")
    public void thePortalStillHoldsFor(int memes, int comments, int favourites, String email) {
        stillHolds(email, memes, comments, favourites);
    }

    private void stillHolds(String email, int memes, int comments, int favourites) {
        assertEquals(memes, portal.memes.heldBy(idOf(email)).size(), "memes destroyed too early");
        assertEquals(comments, portal.comments.heldBy(idOf(email)).size(), "comments destroyed too early");
        assertEquals(favourites, portal.favourites.heldBy(idOf(email)).size(), "favourites destroyed too early");
    }

    @Then("the portal holds nothing of {word}")
    public void thePortalHoldsNothing(String email) {
        assertEquals(0, portal.memes.heldBy(idOf(email)).size(), "memes survived the closure");
        assertEquals(0, portal.comments.heldBy(idOf(email)).size(), "comments survived the closure");
        assertEquals(0, portal.favourites.heldBy(idOf(email)).size(), "favourites survived the closure");
    }

    @Then("security is told the portal purged the content of {word}")
    public void securityIsToldItPurged(String email) {
        assertTrue(said(ClosureMessages.PORTAL_CONTENT_PURGED, email),
                "security was told: " + portal.saidToSecurity());
    }

    @Then("security is told the purge of {word} failed")
    public void securityIsToldItFailed(String email) {
        assertTrue(said(ClosureMessages.PORTAL_PURGE_FAILED, email),
                "security was told: " + portal.saidToSecurity());
    }

    @Then("security is told nothing yet")
    public void securityIsToldNothing() {
        assertEquals(0, portal.saidToSecurity().size(),
                "a verdict left the portal before the case was settled: " + portal.saidToSecurity());
    }

    @Then("their {int} memes and {int} comments are out of sight")
    public void thoseTwoAreOutOfSightToo(int memes, int comments) {
        assertEquals(0, portal.memes.visibleOf(idOf(theLeaver)).size(), "memes still in the gallery");
        assertEquals(0, portal.comments.visibleOf(idOf(theLeaver)).size(), "comments still in their threads");
        assertEquals(memes, portal.memes.heldBy(idOf(theLeaver)).size());
        assertEquals(comments, portal.comments.heldBy(idOf(theLeaver)).size());
    }

    @Then("their {int} comments and {int} favourites are out of sight")
    public void thoseTwoAreOutOfSight(int comments, int favourites) {
        assertEquals(0, portal.comments.visibleOf(idOf(theLeaver)).size(), "comments still in their threads");
        assertEquals(0, portal.favourites.visibleOf(idOf(theLeaver)).size(), "favourites still in the lists");
        assertEquals(comments, portal.comments.heldBy(idOf(theLeaver)).size());
        assertEquals(favourites, portal.favourites.heldBy(idOf(theLeaver)).size());
    }

    @Then("their {int} memes are still in the gallery, because that part never heard")
    public void theSilentPartsContentIsUntouched(int memes) {
        assertEquals(memes, portal.memes.visibleOf(idOf(theLeaver)).size(),
                "a part that never got the command has nothing to hide");
    }

    @Then("{word} sees their {int} memes, {int} comments and {int} favourites again")
    public void everythingIsBack(String email, int memes, int comments, int favourites) {
        assertEquals(memes, portal.memes.visibleOf(idOf(email)).size(), "memes did not come back");
        assertEquals(comments, portal.comments.visibleOf(idOf(email)).size(), "comments did not come back");
        assertEquals(favourites, portal.favourites.visibleOf(idOf(email)).size(), "favourites did not come back");
    }

    @Then("the portal holds {int} comments signed by nobody")
    public void theWordsStayUnsigned(int howMany) {
        assertEquals(howMany, portal.comments.signedByNobody().size(),
                "the thread lost the words an administrator's closure said to keep");
        assertEquals(0, portal.comments.heldBy(idOf(theLeaver)).size(),
                "the leaver's name is still on them");
    }

    @Then("the portal holds {int} meme and {int} comment signed by nobody")
    public void whatTheCommunityKeptStaysUnsigned(int memes, int comments) {
        assertEquals(memes, portal.memes.signedByNobody().size(),
                "the gallery lost a meme the community's verdict said to keep");
        assertEquals(comments, portal.comments.signedByNobody().size(),
                "the thread lost the words the community's verdict said to keep");
    }

    @Then("the portal holds no memes and no favourites of {word}")
    public void theOtherTwoAreGone(String email) {
        assertEquals(0, portal.memes.heldBy(idOf(email)).size(), "memes survived a closure that named them");
        assertEquals(0, portal.favourites.heldBy(idOf(email)).size(), "favourites survived");
    }

    @Then("the portal holds no favourites of {word}")
    public void noFavouritesLeft(String email) {
        assertEquals(0, portal.favourites.heldBy(idOf(email)).size(),
                "a pointer has nothing to keep, so nothing may keep it");
    }

    // ---- the seam, asserted ------------------------------------------------------------------

    @Then("nothing is left under their {word} meme")
    public void theThreadWentWithTheMeme(String which) {
        assertEquals(List.of(), portal.comments.under(memeOfTheLeaver(which)),
                "a thread outlived the meme an account closure destroyed");
    }

    @Then("the {int} comments under their {word} meme are still there")
    public void theThreadIsStillThere(int howMany, String which) {
        assertEquals(howMany, portal.comments.under(memeOfTheLeaver(which)).size(),
                "a thread went that nothing had announced");
    }

    @Then("nobody has their {word} meme saved any more")
    public void nobodyPointsAtTheMeme(String which) {
        assertEquals(0, portal.favourites.pointingAt("meme", memeOfTheLeaver(which)).size(),
                "a stranger's list still points at a meme the closure destroyed");
    }

    @Then("nobody has a comment of their {word} meme saved any more")
    public void nobodyPointsAtItsThread(String which) {
        for (String commentId : strangersComments.getOrDefault(memeOfTheLeaver(which), List.of())) {
            assertEquals(0, portal.favourites.pointingAt("comment", commentId).size(),
                    "a stranger's list still points at a comment that went with the meme");
        }
    }

    @Then("the {word} part confirmed {int} reserved")
    public void thePartConfirmed(String participant, int reserved) {
        assertEquals(reserved, portal.confirmedBy(participant),
                "the count that left the portal is not the count that part reserved");
    }

    /**
     * The gap the confirmation does not cover. It is asserted as a NUMBER rather than as "some
     * rows", because a closure that quietly stopped taking strangers' rows with it would be a
     * different portal and this line is what would say so.
     */
    @Then("{int} rows belonging to other people went too, named in no confirmation")
    public void otherPeoplesRowsWentUncounted(int howMany) {
        long gone = strangersRows.size() - strangersRowsLeft();
        assertEquals(howMany, gone,
                "the cascade took a different number of strangers' rows than this file says");
    }

    @Then("a cascade nobody is waiting for is still on the wire")
    public void aCascadeIsStillInFlight() {
        assertTrue(portal.cascadeIsInFlight(),
                "the closure announced nothing, so there was no cascade to be honest about");
    }

    @Then("the {word} part had confirmed {int} reserved, one of which was gone before it was erased")
    public void thePartConfirmedARowItLost(String participant, int reserved) {
        assertEquals(reserved, portal.confirmedBy(participant));
        assertEquals(0, portal.comments.under(strangersMeme).size(),
                "the other protocol did not take the thread after all");
    }

    @Then("nobody has that comment saved any more")
    public void nobodyPointsAtTheLeaversComment() {
        assertEquals(0, portal.favourites.pointingAt("comment", leaversComment).size(),
                "a stranger's list still points at a comment the closure destroyed");
    }

    @Then("the stranger still has that comment saved")
    public void theStrangerStillPointsAtIt() {
        assertEquals(1, portal.favourites.pointingAt("comment", leaversComment).size(),
                "a pointer was dropped at a comment that is still in its thread, anonymised");
    }

    @Then("the comment they wrote under {word}'s meme is not among them")
    public void theTakenCommentDidNotComeBack(String owner) {
        assertEquals(List.of(), portal.comments.under(strangersMeme),
                "compensation put back a row the other protocol had destroyed");
    }

    private boolean said(String type, String email) {
        return portal.saidToSecurity().stream().anyMatch(said ->
                said.path(ClosureMessages.Field.TYPE).asText().equals(type)
                        && said.path(ClosureMessages.Field.EMAIL).asText().equals(email));
    }


    /** Every scenario in this file is about one leaver; the Background names them. */
    private static final String theLeaver = "alice@example.com";

    /** The meme belonging to somebody who is NOT leaving, in the scenarios that need one. */
    private static final String strangersMeme = ContentIds.of("a-strangers-meme");

    /** The leaver's own comment under that meme — the row both protocols can reach. */
    private static final String leaversComment = ContentIds.of(strangersMeme + "-comment");

    /** Which meme of the leaver's a step means; {@code FakeMemes} numbers them from one. */
    private static String memeOfTheLeaver(String which) {
        return ContentIds.of(theLeaver + "-meme-" + switch (which) {
            case "first" -> 1;
            case "second" -> 2;
            default -> throw new IllegalArgumentException("the leaver has no " + which + " meme");
        });
    }

    /** Strangers' comments per meme of the leaver's, so an assertion can name them again. */
    private final Map<String, List<String>> strangersComments = new LinkedHashMap<>();

    /**
     * One row belonging to somebody who is NOT leaving. Kept as a list rather than a tally so the
     * count in the feature file is a count of NAMED rows: "8 rows went" is only worth writing
     * down if a scenario can say which eight.
     */
    private record StrangersRow(String kind, String memeId, String id) {
    }

    private final List<StrangersRow> strangersRows = new ArrayList<>();

    private long strangersRowsLeft() {
        return strangersRows.stream().filter(row -> switch (row.kind()) {
            case "comment" -> portal.comments.under(row.memeId()).stream()
                    .anyMatch(comment -> comment.id().equals(row.id()));
            case "saved-meme" -> !portal.favourites.pointingAt("meme", row.id()).isEmpty();
            case "saved-comment" -> !portal.favourites.pointingAt("comment", row.id()).isEmpty();
            default -> throw new IllegalStateException("no such row: " + row.kind());
        }).count();
    }
}
