package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.FakeMemeErasure;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.MemeStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Every row this fake holds, one canonical line each, sorted — the meme half of
     * {@link Portal#fingerprint()}.
     *
     * <p>The reservation is read as a BOOLEAN and not as the instant {@code marks()} keeps. When a
     * row was reserved is the clock's business: two schedules that differ only in how many
     * timeouts passed before the same thing happened are not two different outcomes, and a
     * fingerprint carrying the instant would say they are.
     */
    public List<String> rows() {
        return memes.values().stream()
                .map(meme -> "meme " + meme.id()
                        + " by " + meme.authorId().map(UserId::toString).orElse("nobody")
                        + (isMarked(meme.id()) ? " RESERVED" : ""))
                .sorted()
                .toList();
    }

    /** Every id held right now, marked ones included — what "the portal still holds" enumerates. */
    public List<String> everyId() {
        return memes.keySet().stream().sorted().toList();
    }

    /**
     * Every row and every mark as they are now, and the way back to them — what a unit of work that
     * fails leaves behind ({@link UnitsOfWork}).
     *
     * <p>The way back goes through {@link #store}, the same door the saga's own use cases write a
     * mark through, rather than into the map the superclass keeps them in. A mark whose row is gone
     * is put back the same way: the store reads nothing off the record it is handed but the id and
     * the instant, which is why a synthetic one does here what it could not do anywhere else.
     */
    public Snapshot snapshot() {
        Map<String, Meme> rowsThen = new HashMap<>(memes);
        Map<String, Instant> marksThen = marks();
        return () -> {
            memes.clear();
            memes.putAll(rowsThen);
            Set<String> touched = new HashSet<>(marks().keySet());
            touched.addAll(marksThen.keySet());
            for (String id : touched) {
                Instant marked = marksThen.get(id);
                store(new MemeMetadata(id, Optional.empty(), "png",
                        marked == null ? MemeStatus.ACTIVE : MemeStatus.PENDING_ERASURE, marked));
            }
        };
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
