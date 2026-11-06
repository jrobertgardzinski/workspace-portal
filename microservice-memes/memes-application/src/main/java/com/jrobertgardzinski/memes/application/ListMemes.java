package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import java.util.List;

/**
 * One page of the gallery, newest first — the public wall. Paged, not "all": the caller says
 * where to start and how many rows it can take, and the store never has to materialise a gallery
 * that has outgrown the screen.
 */
public class ListMemes {

    private final MemeRepository repository;

    public ListMemes(MemeRepository repository) {
        this.repository = repository;
    }

    public List<String> execute(long offset, int limit) {
        return repository.allIds(offset, limit);
    }
}
