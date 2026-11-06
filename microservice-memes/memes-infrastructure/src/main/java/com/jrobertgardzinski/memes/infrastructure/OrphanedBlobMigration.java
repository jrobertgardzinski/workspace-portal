package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Carries the image bytes left behind in {@code meme_blobs} over to whatever {@link ObjectStore} is
 * ACTIVE, then empties the table.
 *
 * <p>Why this exists. {@code meme_blobs} is the DB store's table and the DB store is the default,
 * but the deployment runs {@code MEMES_BLOB_STORE=s3}: after that switch the {@code DbObjectStore}
 * bean is not even created, so the table is never read, never written and never deleted from — and
 * it still holds full images. The consequences were two, both permanent. Every meme whose bytes
 * stayed behind became a ghost: listed by the gallery, yet 404 from its own picture, and its author
 * could not delete it. And under GDPR the worse half — an account purge removes the meme ROW and
 * asks the ACTIVE store to drop a key that was never there (a no-op), so the person's actual image
 * survived the erasure in {@code meme_blobs} with no code path left that could ever delete it, while
 * the service reported the purge as done.
 *
 * <p>Idempotent (ADR 0006), and deliberately per-object rather than one big transaction:
 * <ul>
 *   <li>nothing to do when the DB store IS the active one — the table is then live data, and
 *       "migrating" it would mean copying rows onto themselves and deleting them;</li>
 *   <li>a key the active store already has is NOT overwritten: what is in the active store is the
 *       truth, and the legacy row is simply dropped;</li>
 *   <li>each object is copied and its row deleted on its own (no surrounding transaction), so a
 *       crash halfway leaves the rest for the next start instead of undoing the work done;</li>
 *   <li>one object that cannot be copied (an S3 hiccup, an unreadable row) is logged and skipped —
 *       it must not cost the service its startup, and its row stays for the next attempt.</li>
 * </ul>
 *
 * <p>No PII in the logs: keys are meme ids, never authors.
 */
@Component
class OrphanedBlobMigration implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(OrphanedBlobMigration.class);

    /** The store whose table {@code meme_blobs} is — with it active, there is nothing to migrate. */
    private static final String DB_STORE = "db";

    private final JdbcClient jdbc;
    private final ObjectStore active;
    private final String configuredStore;

    OrphanedBlobMigration(JdbcClient jdbc, ObjectStore active,
                          @Value("${memes.blob-store:db}") String configuredStore) {
        this.jdbc = jdbc;
        this.active = active;
        this.configuredStore = configuredStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** @return how many objects were moved into the active store (0 when there was nothing to do) */
    int migrate() {
        if (DB_STORE.equalsIgnoreCase(configuredStore.trim())) {
            return 0;   // the table is the active store; leave it alone
        }
        List<String> keys;
        try {
            keys = jdbc.sql("SELECT object_key FROM meme_blobs ORDER BY object_key")
                    .query((rs, n) -> rs.getString("object_key")).list();
        } catch (DataAccessException tableUnreadable) {
            // The service ran for months without ever touching this table; refusing to start
            // because a leftover of an old store cannot be listed would trade a data problem for
            // an outage. Loud, then out of the way.
            LOG.error("could not inspect meme_blobs for leftovers of an earlier blob store — "
                    + "the service starts anyway, but any bytes in there are still unreachable", tableUnreadable);
            return 0;
        }
        if (keys.isEmpty()) {
            return 0;   // the normal case, and the reason a second run is a no-op
        }
        LOG.warn("meme_blobs still holds {} object(s) while the active blob store is '{}' — "
                + "migrating them so their memes can be served and erased again", keys.size(), configuredStore);
        int moved = 0;
        int alreadyInStore = 0;
        int failed = 0;
        for (String key : keys) {
            try {
                if (active.get(key).isPresent()) {
                    alreadyInStore++;      // the active store already answers for this key: it wins
                } else {
                    byte[] data = jdbc.sql("SELECT data FROM meme_blobs WHERE object_key = ?")
                            .param(key).query((rs, n) -> rs.getBytes("data")).optional().orElse(null);
                    if (data == null) {
                        continue;          // gone between the listing and now — nothing to carry
                    }
                    active.put(key, data);
                    moved++;
                }
                // only after the bytes are safely in the active store (or were already there): the
                // legacy row is what makes them unreachable, so it goes last
                jdbc.sql("DELETE FROM meme_blobs WHERE object_key = ?").param(key).update();
            } catch (RuntimeException objectFailed) {
                failed++;
                LOG.error("could not migrate blob {} out of meme_blobs — its row stays for the next "
                        + "start; the meme keeps serving 404 until then", key, objectFailed);
            }
        }
        LOG.warn("meme_blobs migration finished: {} object(s) moved to '{}', {} already there (row dropped), "
                + "{} left behind after an error", moved, configuredStore, alreadyInStore, failed);
        return moved;
    }
}
