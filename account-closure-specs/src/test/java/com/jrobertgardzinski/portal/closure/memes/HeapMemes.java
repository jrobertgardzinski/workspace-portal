package com.jrobertgardzinski.portal.closure.memes;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.application.FakeMemeErasure;
import com.jrobertgardzinski.memes.application.MemeRepository;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeMetadata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The meme service's rows on the heap — a thin subclass of {@link FakeMemeErasure},
 * memes-application's own reference stand-in for {@code MemeErasure}, reached through this
 * repository's test-jar dependency on it. What is added here is the {@code MemeRepository} axis
 * over the SAME backing map (bytes are never read by a saga, so every posted meme carries an empty
 * one) and the convenience readers the specs already call by name.
 *
 * <p>{@code MemeRepository}'s own javadoc says every read is a read of the GALLERY: a meme a
 * running saga has marked must be invisible through it, exactly like the real adapter's
 * {@code active_memes} view. {@link #findMetadata} and {@link #allIds} honour that by asking
 * {@link #isMarked}; the original, fully self-contained version of this class did not, and nothing
 * here exercised the difference — this repository axis is dead weight for the sagas' own specs,
 * still worth answering correctly since it is part of the port.
 */
public final class HeapMemes extends FakeMemeErasure implements MemeRepository {

    private final Map<String, Meme> memes;

    public HeapMemes() {
        this(new HashMap<>());
    }

    private HeapMemes(Map<String, Meme> memes) {
        super(memes);
        this.memes = memes;
    }

    public void posted(String author, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            posted(author + "-meme-" + i, author);
        }
    }

    /** Rows written after the cutover: the author's id beside the address. */
    public void posted(String author, UserId authorId, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            posted(author + "-meme-" + i, author, authorId);
        }
    }

    public void posted(String id, String author) {
        memes.put(id, new Meme(id, author, "png", new byte[0]));
    }

    public void posted(String id, String author, UserId authorId) {
        memes.put(id, new Meme(id, author, Optional.of(authorId), "png", new byte[0]));
    }

    /** Every meme of this author's, marked ones included — what "still on the heap" means. */
    public List<MemeMetadata> heldBy(String author) {
        return Stream.concat(activeOf(author).stream(), pendingOf(author).stream()).toList();
    }

    /** This author's memes that are actually in the gallery right now. */
    public List<MemeMetadata> visibleOf(String author) {
        return activeOf(author);
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
        Meme held = memes.get(id);
        return held == null || isMarked(id)
                ? Optional.empty()
                : Optional.of(new MemeMetadata(held.id(), held.author(), held.authorId(), held.format(),
                        com.jrobertgardzinski.memes.domain.MemeStatus.ACTIVE, null));
    }

    @Override
    public List<String> allIds() {
        return memes.keySet().stream().filter(id -> !isMarked(id)).toList();
    }

    @Override
    public void deleteById(String memeId) {
        memes.remove(memeId);
    }

    @Override
    public void reassignAuthor(String memeId, String newAuthor) {
        Meme held = memes.get(memeId);
        if (held != null) {
            // the id goes with the old author, as in the JDBC adapter
            memes.put(memeId, new Meme(held.id(), newAuthor, held.format(), held.data()));
        }
    }
}
