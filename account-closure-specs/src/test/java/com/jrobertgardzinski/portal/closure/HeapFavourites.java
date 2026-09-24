package com.jrobertgardzinski.portal.closure;

import com.jrobertgardzinski.collections.application.CollectionStore;
import com.jrobertgardzinski.collections.application.ItemErasure;
import com.jrobertgardzinski.collections.domain.ItemRef;
import com.jrobertgardzinski.collections.domain.SavedItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The favourites service's rows, on the heap — the third of three, and a near-copy of the one in
 * collections_account-closure's own tests. The copy is deliberate: this runner may depend on the
 * participants but not on anybody's test sources, and a shared fake would be one more thing that
 * has to be true of every assembly of the portal before these scenarios mean anything.
 */
final class HeapFavourites implements CollectionStore, ItemErasure {

    private final List<SavedItem> rows = new ArrayList<>();

    private boolean same(SavedItem row, String user, String collection, ItemRef ref) {
        return row.user().equals(user) && row.collection().equals(collection) && row.ref().equals(ref);
    }

    @Override
    public boolean add(String user, String collection, ItemRef item) {
        if (rows.stream().anyMatch(row -> same(row, user, collection, item))) {
            return false;
        }
        return rows.add(new SavedItem(user, collection, item));
    }

    @Override
    public boolean remove(String user, String collection, ItemRef item) {
        return rows.removeIf(row -> same(row, user, collection, item));
    }

    @Override
    public List<ItemRef> list(String user, String collection) {
        return rows.stream()
                .filter(row -> row.user().equals(user) && row.collection().equals(collection))
                .filter(row -> !row.isPendingErasure())   // a marked reference is out of every list
                .map(SavedItem::ref)
                .toList();
    }

    @Override
    public List<SavedItem> activeOf(String user) {
        return rows.stream().filter(row -> row.user().equals(user))
                .filter(row -> !row.isPendingErasure()).toList();
    }

    @Override
    public List<SavedItem> pendingOf(String user) {
        return rows.stream().filter(row -> row.user().equals(user))
                .filter(SavedItem::isPendingErasure).toList();
    }

    @Override
    public void store(SavedItem state) {
        for (int i = 0; i < rows.size(); i++) {
            if (same(rows.get(i), state.user(), state.collection(), state.ref())) {
                rows.set(i, state);
                return;
            }
        }
        rows.add(state);
    }

    @Override
    public int eraseMarked(String user) {
        List<SavedItem> doomed = pendingOf(user);
        rows.removeAll(doomed);
        return doomed.size();
    }

    @Override
    public List<SavedItem> pendingSince(Instant cutoff) {
        return rows.stream().filter(SavedItem::isPendingErasure)
                .filter(row -> !row.markedForErasureAt().isAfter(cutoff)).toList();
    }

    /** Everything still held under this address, marked or not — what the closure has to clear. */
    List<SavedItem> heldBy(String user) {
        return rows.stream().filter(row -> row.user().equals(user)).toList();
    }

    /** What this member would still see in their lists. */
    List<SavedItem> visibleOf(String user) {
        return activeOf(user);
    }

    void saved(String user, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            add(user, "favourites", new ItemRef("meme", "someones-meme-" + i));
        }
    }
}
