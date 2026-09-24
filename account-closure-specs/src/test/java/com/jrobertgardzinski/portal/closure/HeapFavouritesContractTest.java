package com.jrobertgardzinski.portal.closure;

import com.jrobertgardzinski.collections.application.ItemErasure;
import com.jrobertgardzinski.collections.application.ItemErasureContract;
import com.jrobertgardzinski.collections.domain.ItemRef;

/** The heap the portal's specs run the saved references on, held to what the real adapter promises. */
class HeapFavouritesContractTest extends ItemErasureContract {

    private final HeapFavourites favourites = new HeapFavourites();

    @Override
    protected ItemErasure erasure() {
        return favourites;
    }

    @Override
    protected void givenSavedItem(String user, String collection, ItemRef ref) {
        favourites.add(user, collection, ref);
    }
}
