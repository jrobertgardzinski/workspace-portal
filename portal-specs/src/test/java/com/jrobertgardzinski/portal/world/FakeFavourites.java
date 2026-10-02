package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.collections.domain.FakeCollectionRepository;
import com.jrobertgardzinski.collections.domain.ItemRef;
import com.jrobertgardzinski.collections.domain.SavedItem;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The favourites service's rows in this process — a thin subclass of
 * {@link FakeCollectionRepository}, collections-application's own reference fake for
 * {@code CollectionRepository}/{@code ItemErasure}, reached through this repository's test-jar
 * dependency on it. Used to be a byte-for-byte copy of that class, kept only because this runner
 * could not see any test source of collections-application's — it can, and always could, since
 * portal-specs already depends on {@code collections-application} as a test-jar for its
 * {@code ItemErasureContractTest}.
 *
 *
 * <p>What is left here is exactly what {@link FakeCollectionRepository} does not do: name a few
 * convenience readers the Gherkin steps and {@code CollectionsClosureParticipantTest} already
 * speak in ({@link #heldBy}, {@link #visibleOf}, {@link #saved}). No erasure logic lives in this
 * class any more — there is nothing left here that could drift from the fake it extends.
 */
public final class FakeFavourites extends FakeCollectionRepository {

    /** Whom this fake has saved for: the repository it extends keys by user and walks none. */
    private final Set<UserId> savers = new LinkedHashSet<>();

    /** Every row of this user's, marked ones included — what "the portal still holds" means. */
    public List<SavedItem> heldBy(UserId userId) {
        return Stream.concat(activeOf(userId).stream(), pendingOf(userId).stream()).toList();
    }

    /** This user's rows that are actually in a list right now. */
    public List<SavedItem> visibleOf(UserId userId) {
        return activeOf(userId);
    }

    /** Saves {@code howMany} distinct favourites for a user. */
    public void saved(UserId userId, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            savedPointingAt(userId, "meme", ContentIds.of("someones-meme-" + i));
        }
    }

    /** One favourite pointing at a named thing — a meme, or a comment under one. */
    public void savedPointingAt(UserId userId, String itemType, String id) {
        savers.add(userId);
        add(userId, "favourites", new ItemRef(itemType, id));
    }

    /**
     * Every row this fake holds, one canonical line each, sorted — the favourites half of
     * {@link Portal#fingerprint()}. Only the people this fake has saved for, for the reason
     * {@link #pointingAt} gives: the repository it extends keys by user and walks none.
     */
    public List<String> rows() {
        return savers.stream()
                .flatMap(userId -> heldBy(userId).stream())
                .map(item -> "saved " + item.ref().itemType() + ":" + item.ref().itemId()
                        + " by " + item.user() + " in " + item.collection()
                        + (item.isPendingErasure() ? " RESERVED" : ""))
                .sorted()
                .toList();
    }

    /**
     * Every row of everybody this fake has saved for, as it is now, and the way back to it — the
     * favourites half of {@link Portal#snapshot()}.
     *
     * <p>The only way in is the port: {@link #add}, {@link #remove} and {@link #store}. A reserved
     * row is not removable — the fake refuses it, exactly as the JDBC twin does — so a row this
     * unit of work both saved and reserved is unmarked first and then taken out, which is the only
     * order the repository allows.
     */
    public Snapshot snapshot() {
        Set<UserId> saversThen = new LinkedHashSet<>(savers);
        Map<UserId, List<SavedItem>> rowsThen = new LinkedHashMap<>();
        savers.forEach(user -> rowsThen.put(user, heldBy(user)));
        return () -> {
            savers.clear();
            savers.addAll(saversThen);
            rowsThen.forEach((user, then) -> {
                for (SavedItem now : heldBy(user)) {
                    if (then.stream().noneMatch(was -> sameRow(was, now))) {
                        store(now.restore());
                        remove(user, now.collection(), now.ref());
                    }
                }
                for (SavedItem was : then) {
                    add(was.user(), was.collection(), was.ref());
                    store(was);
                }
            });
        };
    }

    /** The natural key the schema makes UNIQUE, which is what "the same row" means here. */
    private static boolean sameRow(SavedItem left, SavedItem right) {
        return left.collection().equals(right.collection()) && left.ref().equals(right.ref());
    }

    /**
     * Every row anybody still has pointing at this thing, marked ones included — the reader the
     * deletion specs ask "did the cascade reach it".
     *
     * <p>Only the people this fake has saved for: the repository it stands in for keys everything
     * by user and exposes no way to walk them, and a spec that saved for nobody expects nothing.
     */
    public List<SavedItem> pointingAt(String itemType, String id) {
        ItemRef ref = new ItemRef(itemType, id);
        return savers.stream()
                .flatMap(userId -> Stream.concat(activeOf(userId).stream(), pendingOf(userId).stream()))
                .filter(item -> item.ref().equals(ref))
                .toList();
    }

}
