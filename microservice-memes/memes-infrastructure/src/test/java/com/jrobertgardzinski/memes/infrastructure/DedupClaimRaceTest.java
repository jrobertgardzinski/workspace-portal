package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The dedup claim's promise — "two simultaneous uploads of the same picture race the constraint and
 * exactly one insert wins; the loser reads the winner back" — against the REAL adapter and a real
 * schema.
 *
 * <p>The race's pin used to live one layer up, on a {@code putIfAbsent} fake, so it could not see
 * the only place the promise can break: the SQL. And it did break — the winner claims first and
 * saves afterwards, in a separate transaction, so there is a window in which the claim is committed
 * and the meme row is not. A read of the holder through {@code active_memes} finds nothing in that
 * window and cannot tell it from a meme a deletion saga has reserved.
 *
 * <p>No threads here on purpose: the window is a STATE, not an instant, and it is reproduced by
 * claiming without saving — which is exactly what the winner's thread has done when the loser
 * arrives. A test that waits for a race to happen is a test that passes for the wrong reason.
 */
@Epic("Infrastructure")
@Feature("Content dedup")
@SpringBootTest(classes = MemesApplication.class)
class DedupClaimRaceTest {

    private static final UserId SOMEBODY = UserId.random();

    @Autowired
    MemeContentIndex contentIndex;

    @Autowired
    MemeRepository memes;

    @Test
    @DisplayName("the loser reads the winner back while the winner is still saving")
    void the_loser_reads_the_winner_back_mid_save() {
        byte[] picture = ("pixels-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String winner = UUID.randomUUID().toString();
        assertEquals(winner, contentIndex.claim(picture, winner), "the winner won its claim");
        // ...and has not saved yet. On the S3 store that gap is a full MinIO PUT of up to 10 MB
        // wide, so it is milliseconds to seconds, not microseconds.

        String answer = contentIndex.claim(picture, UUID.randomUUID().toString());

        assertEquals(winner, answer,
                "the loser must be handed the winner's id, not store a second copy of one picture");
    }

    @Test
    @DisplayName("and once the winner has saved, the next upload of that picture dedups as ever")
    void a_saved_winner_still_wins() {
        byte[] picture = ("pixels-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String winner = UUID.randomUUID().toString();
        contentIndex.claim(picture, winner);
        memes.save(new Meme(winner, SOMEBODY, "png", picture));

        assertEquals(winner, contentIndex.claim(picture, UUID.randomUUID().toString()),
                "the ordinary dedup path, unchanged");
    }
}
