package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.domain.MemeRepository;

import java.util.Optional;

/**
 * What is known ABOUT a meme: its id, its uploader, its format. The picture is a separate read
 * ({@link ServeMeme}) behind a separate URL, so this use case never touches the {@link ObjectStore}
 * — the dialog's metadata call and its delete-permission check stopped costing a full image
 * transfer each, and a meme whose bytes are missing from the active store no longer answers "no
 * such meme" to the very person who uploaded it.
 */
public class ViewMeme {

    private final MemeRepository repository;

    public ViewMeme(MemeRepository repository) {
        this.repository = repository;
    }

    public Optional<MemeMetadata> execute(String id) {
        return repository.findMetadata(id);
    }
}
