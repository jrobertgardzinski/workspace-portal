package com.jrobertgardzinski.portal.closure2.closure;

import com.jrobertgardzinski.collections.domain.SavedItem;
import com.jrobertgardzinski.collections.system.PurgeUserItems;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.email.domain.Email;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.system.DeleteMeme;
import com.jrobertgardzinski.portal.closure2.world.Checklist;
import com.jrobertgardzinski.portal.closure2.world.CollectionsPart;
import com.jrobertgardzinski.portal.closure2.world.ContentIds;
import com.jrobertgardzinski.portal.closure2.world.Portal;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.security.domain.vo.AccountClosure;
import com.jrobertgardzinski.security.domain.vo.DeletionInitiator;
import com.jrobertgardzinski.security.domain.vo.PurgeChoices;
import io.cucumber.java.en.And;
import io.cucumber.java.en.But;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The steps of {@code ../specs-2/closing-an-account.feature}.
 *
 * <p>Two rules hold this file together, and both are about what a step may be.
 *
 * <p><strong>One step is one piece of work OR one assertion, never both.</strong> No step both
 * moves the chain along and checks something, and no step quietly does two parts' work — which is
 * why the feature file spells the three parts out three times instead of saying "every part".
 * Without that, an order the file promises could be broken by the code and the file would still
 * pass, because nothing would ever observe the moment in between.
 *
 * <p><strong>An assertion reads a fake directly.</strong> There is no helper layer between a
 * {@code Then} and {@code FakeMemeErasure}/{@code FakeCommentErasure}/{@code FakeCollectionRepository}
 * /{@code FakeUserRepository}, so what a scenario claims is visibly a claim about what those stores
 * hold. In particular <strong>"gone" means "not in the store", never "the repository cannot see
 * it"</strong>: the content repositories hide a hidden row exactly as the deployed adapters' active
 * views do, so an assertion written through {@code find} or {@code findMetadata} would pass the
 * moment something was HIDDEN and claim it had been destroyed.
 */
public class AccountClosureSteps {

    private final Portal portal = new Portal();

    private int memesSeeded;
    private int commentsSeeded;
    private int referencesSeeded;

    private DeleteMeme.Result lastTakeDown;
    private PurgeUserItems.Closure lastCollectionsClosure;

    // ---------------------------------------------------------------- what there is to begin with

    @Given("alice has {int} memes, {int} comments and {int} saved references")
    public void alice_has(int memes, int comments, int references) {
        UserId alice = portal.arrived("alice");
        portal.memes().posted(alice, memes, "alice");
        portal.comments().wrote(alice, comments, "alice");
        portal.collections().saved(alice, references, "alice");
        memesSeeded = memes;
        commentsSeeded = comments;
        referencesSeeded = references;
    }

    @And("bob saved one of alice's memes")
    public void bob_saved_one_of_alices_memes() {
        portal.collections().savedPointingAt(portal.arrived("bob"), "meme", ContentIds.memeOf("alice", 1));
    }

    @And("bob saved alice's first comment")
    public void bob_saved_alices_first_comment() {
        portal.collections().savedPointingAt(portal.arrived("bob"), "comment",
                ContentIds.commentOf("alice", 1));
    }

    @And("{int} people upvoted alice's first comment")
    public void people_upvoted_alices_first_comment(int howMany) {
        portal.comments().upvoted(ContentIds.commentOf("alice", 1), howMany);
    }

    @Given("alice's account cannot be deleted")
    public void alices_account_cannot_be_deleted() {
        portal.identity().accounts().refuseDeletion();
    }

    // ------------------------------------------------------------------------- moving the chain on

    @When("the closure of alice's account is requested")
    public void the_closure_is_requested() {
        portal.closedBy(DeletionInitiator.SELF);
        portal.identity().closureRequested(AccountClosure.requestedByOwner(alice()));
    }

    @When("the closure of alice's account is requested, stating that popular comments be kept")
    public void the_closure_is_requested_stating_a_condition() {
        portal.closedBy(DeletionInitiator.SELF);
        PurgeRule kept = new PurgeRule.KeepPopularAnonymized(2);
        portal.stated("comments", kept);
        // stated by the request and dropped by it: a closure asked for by the account's own owner
        // carries no conditions, whatever the caller sent
        portal.identity().closureRequested(new AccountClosure(alice(), DeletionInitiator.SELF,
                new PurgeChoices(Map.of("comments", kept.asText()))));
    }

    @When("an administrator closes alice's account, keeping the comments at least {int} readers kept")
    public void an_administrator_closes_alices_account(int minReaders) {
        portal.closedBy(DeletionInitiator.ADMIN);
        PurgeRule kept = new PurgeRule.KeepPopularAnonymized(minReaders);
        portal.stated("comments", kept);
        portal.identity().closureRequested(AccountClosure.requestedByAdministrator(alice(),
                new PurgeChoices(Map.of("comments", kept.asText()))));
    }

    @When("{word} has hidden alice's content")
    public void a_part_has_hidden_alices_content(String part) {
        switch (part) {
            case "memes" -> portal.memes().hide(aliceId());
            case "comments" -> portal.comments().hide(aliceId());
            case "collections" -> portal.collections().hide(aliceId());
            default -> throw new IllegalArgumentException("no such part of the portal: " + part);
        }
        checklist().hiddenBy(part);
    }

    @When("{word} has destroyed alice's content")
    public void a_part_has_destroyed_alices_content(String part) {
        assertEquals(Checklist.Verdict.ACCOUNT_GONE, checklist().verdict(),
                "nothing may be destroyed while the account is still there");
        switch (part) {
            case "memes" -> portal.memes().destroy(aliceId(), portal.conditionFor("memes"));
            case "comments" -> portal.comments().destroy(aliceId(), portal.conditionFor("comments"));
            case "collections" -> lastCollectionsClosure = portal.collections().destroy(aliceId());
            default -> throw new IllegalArgumentException("no such part of the portal: " + part);
        }
    }

    @When("{word} has brought alice's content back")
    public void a_part_has_brought_alices_content_back(String part) {
        assertEquals(Checklist.Verdict.ACCOUNT_STAYS, checklist().verdict(),
                "content comes back only when the account stayed");
        switch (part) {
            case "memes" -> portal.memes().bringBack(aliceId());
            case "comments" -> portal.comments().bringBack(aliceId());
            case "collections" -> portal.collections().bringBack(aliceId());
            default -> throw new IllegalArgumentException("no such part of the portal: " + part);
        }
    }

    @When("the closure is given up on")
    public void the_closure_is_given_up_on() {
        checklist().giveUp();
    }

    @When("collections has heard that alice's meme is gone")
    public void collections_has_heard_that_alices_meme_is_gone() {
        List<String> gone = portal.memeAnnouncements().newFor("collections");
        assertFalse(gone.isEmpty(), "collections was told about a meme going and nothing had gone");
        portal.collections().forget("meme", gone);
    }

    @When("alice posts one more meme")
    public void alice_posts_one_more_meme() {
        portal.memes().rows().posted(ContentIds.memeOf("alice", 99), aliceId());
    }

    @When("alice saves one more reference")
    public void alice_saves_one_more_reference() {
        portal.collections().savedPointingAt(aliceId(), "meme", "a-meme-saved-on-the-way-out");
    }

    @When("alice takes her first meme down")
    public void alice_takes_her_first_meme_down() {
        lastTakeDown = portal.memes().takeDown(ContentIds.memeOf("alice", 1));
    }

    // ------------------------------------------------------------------------------ the account

    @Then("alice is still in the user repository")
    public void alice_is_still_in_the_user_repository() {
        assertTrue(portal.identity().users().findBy(alice()).isPresent(),
                "the account was gone and nothing had finished letting go of her content");
    }

    @Then("only now is alice gone from the user repository")
    public void only_now_is_alice_gone_from_the_user_repository() {
        alice_is_gone_from_the_user_repository();
    }

    @Then("alice is gone from the user repository")
    public void alice_is_gone_from_the_user_repository() {
        assertTrue(portal.identity().users().findBy(alice()).isEmpty(), "the account is still there");
    }

    @And("alice is gone from the session, factor and recovery-code repositories")
    public void alice_is_gone_from_the_other_account_stores() {
        assertTrue(portal.identity().sessions().listActiveSessions(alice()).isEmpty(),
                "a session outlived the account");
        assertTrue(portal.identity().factors().findByUser(alice()).isEmpty(),
                "a second factor's secret outlived the account");
        assertEquals(0, portal.identity().recoveryCodes().unusedCount(alice()),
                "a recovery code outlived the account");
    }

    @And("nothing in the portal can be found by alice's address any more")
    public void nothing_can_be_found_by_alices_address() {
        assertTrue(portal.identity().users().findBy(alice()).isEmpty(),
                "the address still leads to an account");
        assertFalse(memesOf(aliceId()).isEmpty(),
                "her content was already gone, so this scenario proves nothing about the two keys");
    }

    @And("bob is still in the user repository")
    public void bob_is_still_in_the_user_repository() {
        assertTrue(portal.identity().users().findBy(portal.emailOf("bob")).isPresent(),
                "bob's account went with somebody else's closure");
    }

    // ------------------------------------------------------------------------------- out of sight

    @Then("alice's memes are out of sight")
    public void alices_memes_are_out_of_sight() {
        assertTrue(portal.memes().rows().activeOf(aliceId()).isEmpty(),
                "a meme of hers is still in the gallery");
    }

    @Then("alice's comments are out of sight")
    public void alices_comments_are_out_of_sight() {
        assertTrue(portal.comments().rows().activeOf(aliceId()).isEmpty(),
                "a comment of hers is still in its thread");
    }

    @Then("alice's saved references are out of sight")
    public void alices_saved_references_are_out_of_sight() {
        assertTrue(portal.collections().rows().activeOf(aliceId()).isEmpty(),
                "a reference of hers is still in her list");
    }

    @But("the meme repository still holds them")
    public void the_meme_repository_still_holds_them() {
        assertEquals(memesSeeded, portal.memes().rows().pendingOf(aliceId()).size(),
                "a meme that is out of sight was destroyed, not hidden");
    }

    @But("the comment repository still holds them")
    public void the_comment_repository_still_holds_them() {
        assertEquals(commentsSeeded, portal.comments().rows().pendingOf(aliceId()).size(),
                "a comment that is out of sight was destroyed, not hidden");
    }

    @But("the collection repository still holds them")
    public void the_collection_repository_still_holds_them() {
        assertEquals(referencesSeeded, portal.collections().rows().pendingOf(aliceId()).size(),
                "a reference that is out of sight was destroyed, not hidden");
    }

    @But("the portal still holds {int} memes, {int} comments and {int} references of hers")
    public void the_portal_still_holds(int memes, int comments, int references) {
        assertEquals(memes, memesOf(aliceId()).size(), "memes held");
        assertEquals(comments, commentsOf(aliceId()).size(), "comments held");
        assertEquals(references, referencesOf(aliceId()).size(), "references held");
    }

    @But("alice's saved references are still in her list")
    public void alices_saved_references_are_still_in_her_list() {
        assertEquals(referencesSeeded, portal.collections().rows().activeOf(aliceId()).size(),
                "a reference of hers left her list");
    }

    // ------------------------------------------------------------------------------------- gone

    @Then("alice's memes are gone")
    public void alices_memes_are_gone() {
        for (int i = 1; i <= memesSeeded; i++) {
            String id = ContentIds.memeOf("alice", i);
            assertFalse(portal.memes().rows().isMarked(id), id + " is hidden, not destroyed");
            assertFalse(portal.memes().rows().allIds().contains(id), id + " is still held");
        }
        assertTrue(memesOf(aliceId()).isEmpty(), "the gallery still holds a meme of hers");
    }

    @Then("alice's comments are gone")
    public void alices_comments_are_gone() {
        for (int i = 1; i <= commentsSeeded; i++) {
            String id = ContentIds.commentOf("alice", i);
            assertFalse(portal.comments().rows().isMarked(id), id + " is hidden, not destroyed");
            assertTrue(portal.comments().rows().find(id).isEmpty(), id + " is still held");
        }
        assertTrue(commentsOf(aliceId()).isEmpty(), "a thread still holds a comment of hers");
    }

    @Then("alice's saved references are gone")
    public void alices_saved_references_are_gone() {
        assertTrue(referencesOf(aliceId()).isEmpty(), "a reference of hers is still held");
    }

    @And("nothing of alice is left anywhere")
    public void nothing_of_alice_is_left_anywhere() {
        assertTrue(portal.identity().users().findBy(alice()).isEmpty(), "the account is still there");
        assertTrue(memesOf(aliceId()).isEmpty(), "a meme of hers is still held");
        assertTrue(commentsOf(aliceId()).isEmpty(), "a comment of hers is still held");
        assertTrue(referencesOf(aliceId()).isEmpty(), "a reference of hers is still held");
    }

    @But("her first two memes are gone")
    public void her_first_two_memes_are_gone() {
        for (int i = 1; i <= 2; i++) {
            String id = ContentIds.memeOf("alice", i);
            assertFalse(portal.memes().rows().isMarked(id), id + " is hidden, not destroyed");
            assertFalse(portal.memes().rows().allIds().contains(id), id + " is still held");
        }
    }

    @But("alice's other comments are gone")
    public void alices_other_comments_are_gone() {
        for (int i = 2; i <= commentsSeeded; i++) {
            String id = ContentIds.commentOf("alice", i);
            assertFalse(portal.comments().rows().isMarked(id), id + " is hidden, not destroyed");
            assertTrue(portal.comments().rows().find(id).isEmpty(), id + " is still held");
        }
        assertTrue(commentsOf(aliceId()).isEmpty(), "a comment is still hers");
    }

    // -------------------------------------------------------------------------------------- back

    @Then("alice's memes are back in the gallery")
    public void alices_memes_are_back_in_the_gallery() {
        assertEquals(memesSeeded, portal.memes().rows().activeOf(aliceId()).size(),
                "a meme of hers did not come back");
        assertTrue(portal.memes().rows().pendingOf(aliceId()).isEmpty(),
                "a meme of hers is still out of sight");
    }

    @Then("alice's comments are back in their threads")
    public void alices_comments_are_back_in_their_threads() {
        assertEquals(commentsSeeded, portal.comments().rows().activeOf(aliceId()).size(),
                "a comment of hers did not come back");
        assertTrue(portal.comments().rows().pendingOf(aliceId()).isEmpty(),
                "a comment of hers is still out of sight");
    }

    @Then("alice's saved references are back in her list")
    public void alices_saved_references_are_back_in_her_list() {
        assertEquals(referencesSeeded, portal.collections().rows().list(aliceId(), CollectionsPart.LIST).size(),
                "a reference of hers did not come back");
        assertTrue(portal.collections().rows().pendingOf(aliceId()).isEmpty(),
                "a reference of hers is still out of sight");
    }

    // ------------------------------------------------------- other people, and what they still see

    @Then("bob still has that meme saved")
    public void bob_still_has_that_meme_saved() {
        assertTrue(portal.collections().holds(portal.idOf("bob"), "meme", ContentIds.memeOf("alice", 1)),
                "bob lost his pointer before the meme it points at went");
    }

    @Then("bob's reference to it is gone")
    public void bobs_reference_to_it_is_gone() {
        assertFalse(portal.collections().holds(portal.idOf("bob"), "meme", ContentIds.memeOf("alice", 1)),
                "bob is still pointing at a meme that is not there");
    }

    @And("bob still has that comment saved")
    public void bob_still_has_that_comment_saved() {
        assertTrue(portal.collections().holds(portal.idOf("bob"), "comment", ContentIds.commentOf("alice", 1)),
                "bob lost his pointer at a comment that is still in its thread");
    }

    @Then("alice's first comment is still in its thread, signed by nobody")
    public void alices_first_comment_is_still_in_its_thread_signed_by_nobody() {
        Optional<Comment> kept = portal.comments().rows().find(ContentIds.commentOf("alice", 1));
        assertTrue(kept.isPresent(), "the comment the readers kept is not in its thread");
        assertTrue(kept.get().authorId().isEmpty(), "the comment still names its author");
        assertTrue(portal.comments().rows().findByMeme(ContentIds.SOMEONE_ELSES_MEME).stream()
                        .anyMatch(comment -> comment.id().equals(ContentIds.commentOf("alice", 1))),
                "the comment is held but no longer hangs in the thread it was written in");
    }

    // ------------------------------------------------------------- what happened during the closure

    @Then("the meme alice posted after that is still in the gallery")
    public void the_meme_alice_posted_after_that_is_still_in_the_gallery() {
        assertTrue(portal.memes().rows().findMetadata(ContentIds.memeOf("alice", 99)).isPresent(),
                "a meme posted after the closure had taken its hold went with it");
    }

    @Then("the reference alice saved after that is still in her list")
    public void the_reference_alice_saved_after_that_is_still_in_her_list() {
        assertTrue(portal.collections().holds(aliceId(), "meme", "a-meme-saved-on-the-way-out"),
                "a reference saved after the closure had taken its hold went with it");
    }

    @And("collections had to leave {int} reference behind")
    public void collections_had_to_leave_references_behind(int howMany) {
        assertEquals(howMany, lastCollectionsClosure.leftBehind(),
                "what the closure could not touch is not counted");
    }

    @Then("alice is told there is no such meme")
    public void alice_is_told_there_is_no_such_meme() {
        assertEquals(DeleteMeme.Status.NO_SUCH_MEME, lastTakeDown.status(),
                "she was told something else about a meme she cannot see");
    }

    // ----------------------------------------------------------------------------------- the reads

    private Email alice() {
        return portal.emailOf("alice");
    }

    private UserId aliceId() {
        return portal.idOf("alice");
    }

    private Checklist checklist() {
        return portal.identity().closures().of(alice()).checklist();
    }

    /** Every meme of hers the store holds, out of sight or not. */
    private List<MemeMetadata> memesOf(UserId who) {
        return Stream.concat(portal.memes().rows().activeOf(who).stream(),
                portal.memes().rows().pendingOf(who).stream()).toList();
    }

    private List<Comment> commentsOf(UserId who) {
        return Stream.concat(portal.comments().rows().activeOf(who).stream(),
                portal.comments().rows().pendingOf(who).stream()).toList();
    }

    private List<SavedItem> referencesOf(UserId who) {
        return Stream.concat(portal.collections().rows().activeOf(who).stream(),
                portal.collections().rows().pendingOf(who).stream()).toList();
    }
}
