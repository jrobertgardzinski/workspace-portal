package com.jrobertgardzinski.portal.closure.collections;

import java.util.Optional;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.collections.application.InMemoryCollectionRepository;
import com.jrobertgardzinski.collections.domain.ItemRef;
import com.jrobertgardzinski.collections.domain.SavedItem;

import java.util.List;
import java.util.stream.Stream;

/**
 * The favourites service's rows on the heap — now a thin subclass of
 * {@link InMemoryCollectionRepository}, collections-application's own reference stand-in for
 * {@code CollectionRepository}/{@code ItemErasure}, reached through this repository's test-jar
 * dependency on it. Used to be a byte-for-byte copy of that class, kept only because this runner
 * could not see any test source of collections-application's — it can, and always could, since
 * account-closure-specs already depends on {@code collections-application} as a test-jar for its
 * {@code ItemErasureContractTest}.
 *
 * <p>What is left here is exactly what {@link InMemoryCollectionRepository} does not do: name a few
 * convenience readers the Gherkin steps and {@code CollectionsClosureParticipantTest} already
 * speak in ({@link #heldBy}, {@link #visibleOf}, {@link #saved}). No erasure logic lives in this
 * class any more — there is nothing left here that could drift from the class it stands in for.
 */
public final class HeapFavourites extends InMemoryCollectionRepository {

    /** Every row of this user's, marked ones included — what "still on the heap" means. */
    public List<SavedItem> heldBy(String user) {
        return Stream.concat(activeOf(user).stream(), pendingOf(user).stream()).toList();
    }

    /** This user's rows that are actually in a list right now. */
    public List<SavedItem> visibleOf(String user) {
        return activeOf(user);
    }

    /** Saves {@code howMany} distinct favourites for a user, named the way the specs read them. */
    public void saved(String user, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            add(user, "favourites", new ItemRef("meme", "someones-meme-" + i));
        }
    }

    /** Rows written after the cutover: the owner's id beside the address. */
    public void saved(String user, UserId userId, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            add(user, Optional.of(userId), "favourites", new ItemRef("meme", "someones-meme-" + i));
        }
    }
}
