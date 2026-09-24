package com.jrobertgardzinski.portal.closure;

import com.jrobertgardzinski.memes.application.MemeErasure;
import com.jrobertgardzinski.memes.application.MemeErasureContract;

/**
 * The heap the portal's specs run the gallery on, held to what the gallery's real adapter
 * promises. It has to be: the adapter lives in another repository and nothing here can see it, so
 * without the contract this stand-in's only reviewer is whoever wrote it — and on the day it was
 * written it answered {@code pendingSince} one row differently.
 */
class HeapMemesContractTest extends MemeErasureContract {

    private final HeapMemes memes = new HeapMemes();

    @Override
    protected MemeErasure erasure() {
        return memes;
    }

    @Override
    protected void givenActiveMeme(String id, String author) {
        memes.posted(id, author);
    }
}
