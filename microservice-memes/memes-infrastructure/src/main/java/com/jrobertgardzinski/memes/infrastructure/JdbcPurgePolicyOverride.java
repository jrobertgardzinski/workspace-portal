package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.PurgePolicyOverride;
import com.jrobertgardzinski.purge.PurgeRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * The admin's purge-policy override, persisted in the generic {@code settings} table (Postgres;
 * H2 in dev/tests) under the {@code purge.memes} key. Only {@link PurgeRule#asText() canonical
 * rule text} is ever written, so a row that fails to parse (a hand-edited database) is treated
 * as no override rather than wedging every purge.
 */
@Repository
class JdbcPurgePolicyOverride implements PurgePolicyOverride {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcPurgePolicyOverride.class);

    private static final String KEY = "purge.memes";

    private final JdbcClient jdbc;

    JdbcPurgePolicyOverride(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PurgeRule> current() {
        return jdbc.sql("SELECT setting_value FROM settings WHERE setting_key = ?")
                .params(KEY).query(String.class).optional()
                .flatMap(JdbcPurgePolicyOverride::parsedQuietly);
    }

    @Override
    public void set(PurgeRule rule, String updatedBy) {
        // ONE statement, the shape JdbcVoteRepository.cast uses for the same reason. It used to be
        // a DELETE and then an INSERT on two autocommit connections, which leaves a GAP where the
        // dial reads as unset: a purge command landing in it (PurgeUserContent resolves
        // "requested, else this override, else the deployment default" on the listener thread)
        // applied the deployment default past the saga's irreversible pivot — content the
        // operator's rule said to keep, deleted, with nothing in the log to explain it. Two admins
        // saving at once used to race the same gap into a 23505 and a 500.
        jdbc.sql("MERGE INTO settings USING (VALUES (?, ?, ?, ?)) "
                        + "AS src(setting_key, setting_value, updated_at, updated_by) "
                        + "ON settings.setting_key = src.setting_key "
                        + "WHEN MATCHED THEN UPDATE SET setting_value = src.setting_value, "
                        + "updated_at = src.updated_at, updated_by = src.updated_by "
                        + "WHEN NOT MATCHED THEN INSERT (setting_key, setting_value, updated_at, updated_by) "
                        + "VALUES (src.setting_key, src.setting_value, src.updated_at, src.updated_by)")
                .params(KEY, rule.asText(), Timestamp.from(Instant.now()), updatedBy).update();
    }

    @Override
    public void clear(String clearedBy) {
        // the ROW is the audit record, and clearing takes it away — so what it said and who took
        // it away are written down before the DELETE. Without this the one mutating route of the
        // three recorded nobody at all, while every leaver whose client states no policy was
        // judged by the deployment default from then on.
        LOG.warn("purge-policy override cleared by {} (it said: {}) — the memes axis follows the"
                + " deployment default again", clearedBy, current().map(PurgeRule::asText).orElse("nothing"));
        jdbc.sql("DELETE FROM settings WHERE setting_key = ?").params(KEY).update();
    }

    private static Optional<PurgeRule> parsedQuietly(String text) {
        try {
            return Optional.of(PurgeRule.parse(text));
        } catch (IllegalArgumentException handEdited) {
            return Optional.empty();
        }
    }
}
