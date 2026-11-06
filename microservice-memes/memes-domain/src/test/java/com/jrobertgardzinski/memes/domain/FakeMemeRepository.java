package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.identity.UserId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link MemeRepository} over the same map {@link FakeMemeErasure} keeps its marks
 * against, so one object answers both axes of a meme: what it is, and whether it is on its way out.
 *
 * <p><strong>Every read here is a read of the GALLERY</strong>, which is what the port's own
 * javadoc says and what the adapter's {@code active_memes} view enforces: {@link #findMetadata}
 * and {@link #allIds} do not see a meme a running deletion has marked, while
 * {@link FakeMemeErasure#isMarked} still remembers it. {@link #deleteById} is status-blind, as the
 * adapter's delete is.
 *
 * <p>Beside the port and published by this module's test-jar, so a consumer that needs the
 * repository axis — the service's own tests, the portal's specs, a suite driving nothing but
 * {@code *-domain} and {@code *-system} — has no reason to write a fourth copy of it.
 */
public class FakeMemeRepository extends FakeMemeErasure implements MemeRepository {

    /** The rows, shared with the erasure axis above: one meme, one entry, two ways of asking. */
    protected final Map<String, Meme> memes;

    public FakeMemeRepository() {
        this(new HashMap<>());
    }

    protected FakeMemeRepository(Map<String, Meme> memes) {
        super(memes);
        this.memes = memes;
    }

    /**
     * One meme of this person's, with no bytes behind it.
     *
     * <p>A closure never reads the image, so a seeded meme carries an empty array rather than an
     * invented one — the absence says the bytes are not part of what is being proven.
     */
    public void posted(String id, UserId author) {
        memes.put(id, new Meme(id, author, "png", new byte[0]));
    }

    @Override
    public void save(Meme meme) {
        memes.put(meme.id(), meme);
    }

    @Override
    public Optional<Meme> find(String id) {
        return isMarked(id) ? Optional.empty() : Optional.ofNullable(memes.get(id));
    }

    @Override
    public Optional<MemeMetadata> findMetadata(String id) {
        Meme held = memes.get(id);
        return held == null || isMarked(id) ? Optional.empty() : Optional.of(metadataOf(held));
    }

    @Override
    public List<String> allIds() {
        return memes.keySet().stream().filter(id -> !isMarked(id)).sorted().toList();
    }

    @Override
    public void deleteById(String memeId) {
        memes.remove(memeId);
        // the status is a column of the row in the schema, so it goes with it; here the marks live
        // in a map of their own and have to be told
        forgetMark(memeId);
    }

    @Override
    public void anonymise(String memeId) {
        Meme held = memes.get(memeId);
        if (held != null) {
            // the author id goes and nothing takes its place, as in the JDBC adapter
            memes.put(memeId, new Meme(held.id(), Optional.empty(), held.format(), held.data()));
        }
    }
}
