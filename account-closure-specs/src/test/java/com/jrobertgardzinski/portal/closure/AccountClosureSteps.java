package com.jrobertgardzinski.portal.closure;

import com.fasterxml.jackson.databind.JsonNode;
import com.jrobertgardzinski.closure.ClosureInitiator;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.memes.domain.DeletedAccount;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The portal's promises about closing an account, driven against all four parts at once. */
public class AccountClosureSteps {

    private PortalInOneProcess portal;

    @Before
    public void wire() {
        portal = new PortalInOneProcess();
    }

    @Given("{word} posted {int} memes, wrote {int} comments and saved {int} favourites")
    public void aMemberWithContent(String email, int memes, int comments, int favourites) {
        portal.memes.posted(email, memes);
        portal.comments.wrote(email, comments);
        portal.favourites.saved(email, favourites);
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
        // the command never reaches it — the one failure these scenarios stage, and the only one
        // that cannot be told from inside any single repository
        portal.silence(silent);
        portal.everyPartAnswers();
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
        assertEquals(memes, portal.memes.heldBy(email).size(), "memes destroyed too early");
        assertEquals(comments, portal.comments.heldBy(email).size(), "comments destroyed too early");
        assertEquals(favourites, portal.favourites.heldBy(email).size(), "favourites destroyed too early");
    }

    @Then("the portal holds nothing of {word}")
    public void thePortalHoldsNothing(String email) {
        assertEquals(0, portal.memes.heldBy(email).size(), "memes survived the closure");
        assertEquals(0, portal.comments.heldBy(email).size(), "comments survived the closure");
        assertEquals(0, portal.favourites.heldBy(email).size(), "favourites survived the closure");
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
        assertEquals(0, portal.memes.visibleOf(theLeaver).size(), "memes still in the gallery");
        assertEquals(0, portal.comments.visibleOf(theLeaver).size(), "comments still in their threads");
        assertEquals(memes, portal.memes.heldBy(theLeaver).size());
        assertEquals(comments, portal.comments.heldBy(theLeaver).size());
    }

    @Then("their {int} comments and {int} favourites are out of sight")
    public void thoseTwoAreOutOfSight(int comments, int favourites) {
        assertEquals(0, portal.comments.visibleOf(theLeaver).size(), "comments still in their threads");
        assertEquals(0, portal.favourites.visibleOf(theLeaver).size(), "favourites still in the lists");
        assertEquals(comments, portal.comments.heldBy(theLeaver).size());
        assertEquals(favourites, portal.favourites.heldBy(theLeaver).size());
    }

    @Then("their {int} memes are still in the gallery, because that part never heard")
    public void theSilentPartsContentIsUntouched(int memes) {
        assertEquals(memes, portal.memes.visibleOf(theLeaver).size(),
                "a part that never got the command has nothing to hide");
    }

    @Then("{word} sees their {int} memes, {int} comments and {int} favourites again")
    public void everythingIsBack(String email, int memes, int comments, int favourites) {
        assertEquals(memes, portal.memes.visibleOf(email).size(), "memes did not come back");
        assertEquals(comments, portal.comments.visibleOf(email).size(), "comments did not come back");
        assertEquals(favourites, portal.favourites.visibleOf(email).size(), "favourites did not come back");
    }

    @Then("the portal holds {int} comments signed by nobody")
    public void theWordsStayUnsigned(int howMany) {
        assertEquals(howMany, portal.comments.visibleOf(DeletedAccount.AUTHOR).size(),
                "the thread lost the words an administrator's closure said to keep");
        assertEquals(0, portal.comments.heldBy(theLeaver).size(),
                "the leaver's name is still on them");
    }

    @Then("the portal holds no memes and no favourites of {word}")
    public void theOtherTwoAreGone(String email) {
        assertEquals(0, portal.memes.heldBy(email).size(), "memes survived a closure that named them");
        assertEquals(0, portal.favourites.heldBy(email).size(), "favourites survived");
    }

    @Then("the portal holds no favourites of {word}")
    public void noFavouritesLeft(String email) {
        assertEquals(0, portal.favourites.heldBy(email).size(),
                "a pointer has nothing to keep, so nothing may keep it");
    }

    private boolean said(String type, String email) {
        return portal.saidToSecurity().stream().anyMatch(said ->
                said.path(ClosureMessages.Field.TYPE).asText().equals(type)
                        && said.path(ClosureMessages.Field.EMAIL).asText().equals(email));
    }

    /** Every scenario in this file is about one leaver; the Background names them. */
    private static final String theLeaver = "alice@example.com";
}
