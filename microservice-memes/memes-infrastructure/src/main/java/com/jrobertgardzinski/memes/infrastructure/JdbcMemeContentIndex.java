package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Postgres-backed {@link MemeContentIndex} (H2 in dev/tests): the SHA-256 is the primary key, so
 * the CLAIM is the database's own uniqueness — two simultaneous uploads of the same picture race
 * the constraint and exactly one insert wins; the loser reads the winner back.
 */
@Repository
class JdbcMemeContentIndex implements MemeContentIndex {

    private final JdbcClient jdbc;
    private final JdbcMemeErasure erasure;

    JdbcMemeContentIndex(JdbcClient jdbc, JdbcMemeErasure erasure) {
        this.jdbc = jdbc;
        this.erasure = erasure;
    }

    @Override
    public String claim(byte[] data, String candidateId) {
        String hash = sha256(data);
        try {
            jdbc.sql("INSERT INTO content_index (content_hash, meme_id) VALUES (?, ?)")
                    .params(hash, candidateId).update();
            return candidateId;
        } catch (DuplicateKeyException alreadyClaimed) {
            // Who holds the hash — asked of the index alone, which is the only question with a
            // definite answer here. The holder's own meme row is NOT a usable test of whether the
            // claim is live: the winner of this very race claims first and saves afterwards, in a
            // separate transaction that on the S3 store includes a full MinIO PUT, so for
            // milliseconds to seconds the claim exists while the meme row does not. Reading the
            // holder through active_memes therefore told the loser "nobody is there" and sent it
            // down the take-over branch below — two memes of one picture from an ordinary
            // simultaneous upload, and the promise this class states at the top quietly untrue.
            Optional<String> holder = jdbc.sql("SELECT meme_id FROM content_index WHERE content_hash = ?")
                    .params(hash).query((rs, n) -> rs.getString("meme_id")).optional();
            // RESERVED is the one state that must not be handed back, and it is asked for by name.
            // This is the read path the erasure work nearly missed: a leaver's meme marked
            // PENDING_ERASURE still held its hash, so re-uploading that picture answered with the
            // marked meme's id — a 200 pointing at content the gallery refuses to show, and a way
            // to prove by experiment that a given image had been posted by the account being
            // deleted.
            if (holder.isPresent() && !erasure.isReserved(holder.get())) {
                return holder.get();
            }
            // The holder is reserved (or the index row vanished under us): the newcomer takes the
            // hash over, so the upload succeeds as its own meme. If the saga is compensated and the
            // old meme comes back, the two identical pictures simply both exist — dedup is a
            // storage economy, never a correctness rule, and one duplicate is a far smaller wrong
            // than either refusing the upload or pointing it at somebody else's erasure.
            //
            // What this branch deliberately no longer catches is a claim whose meme never arrives
            // at all — a publish whose save failed AND whose compensating remove failed too. That
            // is not a state this read can tell apart from a save still in flight, and PublishMeme
            // already owns it: it releases the claim on a failed save, and logs the double failure
            // in so many words ("identical re-uploads will dedup into a ghost"). Taking the hash
            // over on every absent row bought that one rare case by breaking the ordinary one.
            jdbc.sql("UPDATE content_index SET meme_id = ? WHERE content_hash = ?")
                    .params(candidateId, hash).update();
            return candidateId;
        }
    }

    @Override
    public void remove(String memeId) {
        jdbc.sql("DELETE FROM content_index WHERE meme_id = ?").params(memeId).update();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available
        }
    }
}
