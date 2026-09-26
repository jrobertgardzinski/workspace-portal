package com.jrobertgardzinski.portal.closure.memes;

import com.jrobertgardzinski.memes.application.MemeErasure;
import com.jrobertgardzinski.memes.application.MemeRepository;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeMetadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The meme service's rows on the heap; {@link #store} writes the erasure columns only, like the JDBC adapter. */
public final class HeapMemes implements MemeErasure, MemeRepository {

    private final List<MemeMetadata> rows = new ArrayList<>();

    public void posted(String author, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            posted(author + "-meme-" + i, author);
        }
    }

    public void posted(String id, String author) {
        rows.add(new MemeMetadata(id, author, "png"));
    }

    public List<MemeMetadata> heldBy(String author) {
        return rows.stream().filter(row -> row.author().equals(author)).toList();
    }

    public List<MemeMetadata> visibleOf(String author) {
        return heldBy(author).stream().filter(row -> !row.isPendingErasure()).toList();
    }

    @Override
    public List<MemeMetadata> activeOf(String author) {
        return visibleOf(author);
    }

    @Override
    public List<MemeMetadata> pendingOf(String author) {
        return heldBy(author).stream().filter(MemeMetadata::isPendingErasure).toList();
    }

    @Override
    public void store(MemeMetadata state) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(state.id())) {
                MemeMetadata held = rows.get(i);
                rows.set(i, new MemeMetadata(held.id(), held.author(), held.format(),
                        state.status(), state.markedForErasureAt()));
                return;
            }
        }
    }

    @Override
    public List<MemeMetadata> pendingSince(Instant cutoff) {
        return rows.stream().filter(MemeMetadata::isPendingErasure)
                .filter(row -> row.markedForErasureAt().isBefore(cutoff)).toList();
    }

    @Override
    public void deleteById(String memeId) {
        rows.removeIf(row -> row.id().equals(memeId));
    }

    @Override
    public void reassignAuthor(String memeId, String newAuthor) {
        for (int i = 0; i < rows.size(); i++) {
            MemeMetadata held = rows.get(i);
            if (held.id().equals(memeId)) {
                rows.set(i, new MemeMetadata(held.id(), newAuthor, held.format(), held.status(),
                        held.markedForErasureAt()));
                return;
            }
        }
    }

    @Override
    public List<String> allIds() {
        return rows.stream().map(MemeMetadata::id).toList();
    }

    // posting and reading a meme are not part of closing an account
    @Override
    public void save(Meme meme) {
        throw new UnsupportedOperationException("posting is not part of closing an account");
    }

    @Override
    public Optional<Meme> find(String id) {
        throw new UnsupportedOperationException("reading a meme is not part of closing an account");
    }

    @Override
    public Optional<MemeMetadata> findMetadata(String id) {
        return rows.stream().filter(row -> row.id().equals(id)).findFirst();
    }
}
