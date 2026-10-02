package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.FakeMemeRepository;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
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
 * The meme service's rows in this process — a thin subclass of {@link FakeMemeRepository},
 * memes-domain's own reference fake for the repository and the erasure axis, reached through this
 * repository's test-jar dependency on it.
 *
 * <p>Nothing of the port is implemented here any more: both axes, and the gallery semantics of
 * every read, live beside the port where the contract test holds them to the adapter. What is left
 * is what this runner alone needs — seeding by the ids {@link ContentIds} mints, the readers the
 * Gherkin steps call by name, and the way back that {@link UnitsOfWork} needs.
 */
public final class FakeMemes extends FakeMemeRepository {

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

    /** Every meme of this person's, marked ones included — what "the portal still holds" means. */
    public List<MemeMetadata> heldBy(UserId userId) {
        return Stream.concat(activeOf(userId).stream(), pendingOf(userId).stream()).toList();
    }

    /** This person's memes that are actually in the gallery right now. */
    public List<MemeMetadata> visibleOf(UserId userId) {
        return activeOf(userId);
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
     * <p>The way back goes through {@link #store}, the same door the use cases write a mark
     * through, rather than into the map the superclass keeps them in. A mark whose row is gone is
     * put back the same way: the store reads nothing off the record it is handed but the id and the
     * instant, which is why a synthetic one does here what it could not do anywhere else.
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
}
