package com.jrobertgardzinski.portal.world;

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
 * The meme service's rows in this process — a thin subclass of {@link FakeMemeErasure},
 * memes-application's own reference fake for {@code MemeErasure}, reached through this
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
public final class FakeMemes extends FakeMemeErasure implements MemeRepository {

    private final Map<String, Meme> memes;

    public FakeMemes() {
        this(new HashMap<>());
    }

    private FakeMemes(Map<String, Meme> memes) {
        super(memes);
        this.memes = memes;
    }

    /**
     * {@code howMany} memes of one person, under the ids {@link ContentIds} mints for
     * {@code <prefix>-meme-<n>} — the same ids a step asking for "their first meme" gets back,
     * and ids the deletion cascade's wire contract will actually carry.
     */
    public void posted(String prefix, UserId userId, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            posted(ContentIds.of(prefix + "-meme-" + i), userId);
        }
    }

    public void posted(String id, UserId userId) {
        memes.put(id, new Meme(id, userId, "png", new byte[0]));
    }

    /** Every meme of this person's, marked ones included — what "the portal still holds" means. */
    public List<MemeMetadata> heldBy(UserId userId) {
        return Stream.concat(activeOf(userId).stream(), pendingOf(userId).stream()).toList();
    }

    /** This person's memes that are actually in the gallery right now. */
    public List<MemeMetadata> visibleOf(UserId userId) {
        return activeOf(userId);
    }

    private MemeMetadata metadataOf(Meme meme) {
        return new MemeMetadata(meme.id(), meme.authorId(), meme.format(),
                isMarked(meme.id()) ? com.jrobertgardzinski.memes.domain.MemeStatus.PENDING_ERASURE
                        : com.jrobertgardzinski.memes.domain.MemeStatus.ACTIVE,
                isMarked(meme.id()) ? java.time.Instant.EPOCH : null);
    }

    /** The memes an administrator's closure kept: still in the gallery, belonging to nobody. */
    public List<MemeMetadata> signedByNobody() {
        return memes.values().stream().map(this::metadataOf)
                .filter(meme -> meme.authorId().isEmpty())
                .filter(meme -> !meme.isPendingErasure())
                .toList();
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
                : Optional.of(new MemeMetadata(held.id(), held.authorId(), held.format(),
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
    public void anonymise(String memeId) {
        Meme held = memes.get(memeId);
        if (held != null) {
            // the id goes and nothing takes its place, as in the JDBC adapter
            memes.put(memeId, new Meme(held.id(), Optional.empty(), held.format(), held.data()));
        }
    }
}
