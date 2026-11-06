package com.jrobertgardzinski.memes.domain;

import java.util.Optional;
import com.jrobertgardzinski.identity.UserId;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;

import java.util.HashMap;
import java.util.Map;

/** The stand-in these use-case tests run on, held to the same promises as the real adapter. */
@Epic("Architecture")
@Feature("A stand-in behaves like the adapter it stands in for")
class FakeMemeErasureTest extends MemeErasureContractTest {

    private final Map<String, Meme> memes = new HashMap<>();
    private final FakeMemeErasure erasure = new FakeMemeErasure(memes);

    @Override
    protected MemeErasure erasure() {
        return erasure;
    }

    @Override
    protected void givenActiveMeme(String id, Optional<UserId> authorId) {
        memes.put(id, new Meme(id, authorId, "png", new byte[]{1}));
    }
}
