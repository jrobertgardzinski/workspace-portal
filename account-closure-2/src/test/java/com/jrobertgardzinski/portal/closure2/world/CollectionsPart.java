package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.collections.domain.FakeCollectionRepository;
import com.jrobertgardzinski.collections.domain.ItemRef;
import com.jrobertgardzinski.collections.system.MarkUserItemsForErasure;
import com.jrobertgardzinski.collections.system.PurgeDeletedItem;
import com.jrobertgardzinski.collections.system.PurgeUserItems;
import com.jrobertgardzinski.collections.system.RestoreUserItems;
import com.jrobertgardzinski.identity.UserId;

import java.time.Clock;
import java.util.List;

/**
 * The saved references: collections-domain's own fake of its ports, and the four
 * collections-system use cases.
 *
 * <p>This part is in the chain TWICE, for two unrelated reasons, and the feature file keeps the two
 * apart on purpose. Its own share of a closure is {@link PurgeUserItems}: the leaver's rows, found
 * by the leaver's id. Its share of somebody ELSE's content going is {@link PurgeDeletedItem}: a
 * meme or a comment is gone, so every reference to it goes, in whoever's list it sits — and the
 * person losing a row there is not leaving at all.
 */
public final class CollectionsPart {

    public static final String LIST = "favourites";

    private final FakeCollectionRepository rows = new FakeCollectionRepository();

    private final MarkUserItemsForErasure hide;
    private final PurgeUserItems destroy;
    private final RestoreUserItems bringBack;
    private final PurgeDeletedItem forget;

    CollectionsPart(Clock clock) {
        this.hide = new MarkUserItemsForErasure(rows, clock);
        this.destroy = new PurgeUserItems(rows);
        this.bringBack = new RestoreUserItems(rows);
        this.forget = new PurgeDeletedItem(rows);
    }

    /** The rows, for the steps to seed and to read. */
    public FakeCollectionRepository rows() {
        return rows;
    }

    public void saved(UserId who, int howMany, String name) {
        for (int i = 1; i <= howMany; i++) {
            rows.add(who, LIST, new ItemRef("meme", name + "-saved-" + i));
        }
    }

    public void savedPointingAt(UserId who, String itemType, String itemId) {
        rows.add(who, LIST, new ItemRef(itemType, itemId));
    }

    public boolean holds(UserId who, String itemType, String itemId) {
        return rows.list(who, LIST).contains(new ItemRef(itemType, itemId));
    }

    public void hide(UserId leaver) {
        hide.execute(leaver);
    }

    /** Answers what it had to leave behind — a row saved after the closure had reserved the rest. */
    public PurgeUserItems.Closure destroy(UserId leaver) {
        return destroy.execute(leaver);
    }

    public void bringBack(UserId leaver) {
        bringBack.execute(leaver);
    }

    public int forget(String itemType, List<String> itemIds) {
        return forget.execute(itemType, itemIds);
    }
}
