package com.jrobertgardzinski.portal.closure.memes;

import com.jrobertgardzinski.memes.application.MemeErasure;
import com.jrobertgardzinski.memes.application.MemeErasureContractTest;

/** The heap the portal's specs run the gallery on, held to what the real adapter promises. */
class HeapMemesTest extends MemeErasureContractTest {

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
