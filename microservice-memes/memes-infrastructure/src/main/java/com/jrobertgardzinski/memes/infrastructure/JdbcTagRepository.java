package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/** Postgres-backed {@link TagRepository} (H2 in dev/tests): both directions from one table. */
@Repository
class JdbcTagRepository implements TagRepository {

    private final JdbcClient jdbc;

    JdbcTagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void replaceTags(String memeId, Set<Tag> tags) {
        jdbc.sql("DELETE FROM meme_tags WHERE meme_id = ?").params(memeId).update();
        for (Tag tag : tags) {
            jdbc.sql("INSERT INTO meme_tags (meme_id, tag) VALUES (?, ?)")
                    .params(memeId, tag.value()).update();
        }
    }

    @Override
    public Set<Tag> tagsOf(String memeId) {
        // joined to active_memes, like every other public read: the tag list is served to anyone,
        // so a meme a running account-deletion saga has reserved must not hand its tags out — nor
        // answer "I exist" by returning a non-empty list where a never-uploaded id returns none.
        // The tag rows themselves stay untouched; the mark hides, it does not destroy.
        return jdbc.sql("SELECT t.tag FROM meme_tags t JOIN active_memes m ON m.id = t.meme_id "
                        + "WHERE t.meme_id = ?")
                .params(memeId)
                .query((rs, n) -> new Tag(rs.getString("tag")))
                .list().stream().collect(Collectors.toSet());
    }

    @Override
    public Set<String> memesTagged(Tag tag) {
        return jdbc.sql("SELECT meme_id FROM meme_tags WHERE tag = ?")
                .params(tag.value())
                .query((rs, n) -> rs.getString("meme_id"))
                .list().stream().collect(Collectors.toSet());
    }

    @Override
    public void removeMeme(String memeId) {
        jdbc.sql("DELETE FROM meme_tags WHERE meme_id = ?").params(memeId).update();
    }
}
