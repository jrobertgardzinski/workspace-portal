package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ContentFlags;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * Postgres-backed {@link ContentFlags} (H2 in dev/tests). One row per flagged meme; the foreign
 * key cascades, so deleting a meme (moderation or the account-deletion purge) takes its flags
 * with it and nothing here needs to know.
 */
@Repository
class JdbcContentFlags implements ContentFlags {

    private final JdbcClient jdbc;

    JdbcContentFlags(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void setNsfw(String memeId, boolean nsfw) {
        jdbc.sql("DELETE FROM meme_flags WHERE meme_id = ?").params(memeId).update();
        if (nsfw) {
            jdbc.sql("INSERT INTO meme_flags (meme_id, nsfw) VALUES (?, TRUE)").params(memeId).update();
        }
    }

    @Override
    public boolean isNsfw(String memeId) {
        return jdbc.sql("SELECT COUNT(*) FROM meme_flags WHERE meme_id = ? AND nsfw")
                .params(memeId).query(Long.class).single() > 0;
    }

    @Override
    public Set<String> nsfwIds() {
        // .list() materialises and closes the connection; .stream() would keep the cursor open
        return Set.copyOf(jdbc.sql("SELECT meme_id FROM meme_flags WHERE nsfw")
                .query(String.class).list());
    }
}
