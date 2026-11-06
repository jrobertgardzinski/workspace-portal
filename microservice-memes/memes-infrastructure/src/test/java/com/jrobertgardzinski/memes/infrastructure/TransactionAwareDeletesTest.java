package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the parking rules of {@link TransactionAwareDeletes}: inside a transaction a side effect
 * waits for the commit (and a rollback drops it), outside one it runs immediately — and once the
 * commit has happened every parked runnable is best-effort in isolation: one blowing up must
 * neither abort the others nor surface out of {@code tx.execute}.
 */
@Epic("Infrastructure")
@Feature("Meme deletion")
@Story("After-commit deletes")
@SpringBootTest(classes = MemesApplication.class)
class TransactionAwareDeletesTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    javax.sql.DataSource dataSource;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("inside a transaction the delete is parked until the commit, not run eagerly")
    void inside_a_transaction_the_delete_waits_for_the_commit() {
        List<String> ran = new ArrayList<>();

        tx.executeWithoutResult(status -> {
            TransactionAwareDeletes.afterCommitOrNow(() -> ran.add("delete"));
            assertTrue(ran.isEmpty(), "the delete must not run before the transaction commits");
        });

        assertEquals(List.of("delete"), ran, "after the commit the parked delete has run");
    }

    @Test
    @DisplayName("a rollback drops the parked delete — the object stays with its restored row")
    void a_rollback_drops_the_parked_delete() {
        List<String> ran = new ArrayList<>();

        tx.executeWithoutResult(status -> {
            TransactionAwareDeletes.afterCommitOrNow(() -> ran.add("delete"));
            status.setRollbackOnly();
        });

        assertTrue(ran.isEmpty(), "a rolled-back transaction must not fire its parked deletes");
    }

    @Test
    @DisplayName("one after-commit runnable blowing up neither cuts down the next nor escapes tx.execute")
    void a_failing_runnable_does_not_block_the_others_nor_the_caller() {
        List<String> ran = new ArrayList<>();

        assertDoesNotThrow(() -> tx.executeWithoutResult(status -> {
            TransactionAwareDeletes.afterCommitOrNow(() -> {
                ran.add("first");
                throw new IllegalStateException("store hiccup after commit");
            });
            TransactionAwareDeletes.afterCommitOrNow(() -> ran.add("second"));
        }), "the transaction IS committed — a failed after-commit cleanup must not look like a failed delete");

        assertEquals(List.of("first", "second"), ran,
                "the second parked delete must run even though the first one threw");
    }

    @Test
    @DisplayName("outside a transaction the delete runs immediately")
    void outside_a_transaction_the_delete_runs_now() {
        List<String> ran = new ArrayList<>();

        TransactionAwareDeletes.afterCommitOrNow(() -> ran.add("delete"));

        assertEquals(List.of("delete"), ran);
    }

    @Test
    @DisplayName("a delete issued from INSIDE an after-commit runnable executes — it must not park into the void")
    void a_registration_from_inside_after_commit_still_executes() {
        // The trap: Spring iterates a SNAPSHOT of the synchronizations per phase, so a
        // registration made from within an afterCommit callback is never invoked in that phase.
        // This is a real path, not a curiosity — the repository's after-commit variant re-sweep
        // calls ObjectStore.delete, which on filesystem/S3 parks itself again. The parked side
        // effect listens on afterCompletion too (a FRESH snapshot), so the late registration is
        // still delivered — after the commit, before the cleanup — instead of lost silently.
        List<String> ran = new ArrayList<>();

        tx.executeWithoutResult(status ->
                TransactionAwareDeletes.afterCommitOrNow(() -> {
                    ran.add("outer");
                    TransactionAwareDeletes.afterCommitOrNow(() -> ran.add("inner"));
                }));

        assertEquals(List.of("outer", "inner"), ran,
                "the delete issued inside the after-commit phase must have executed, not parked forever");
    }

    @Test
    @DisplayName("a REQUIRES_NEW opened inside an after-commit callback parks the delete until ITS commit")
    void a_requires_new_inside_the_callback_parks_on_the_new_transaction() {
        // The round-3 finding: the old "already after commit → run inline" shortcut was checked
        // BEFORE the active-transaction check, so a runnable opening REQUIRES_NEW inside an
        // after-commit callback and issuing a delete had it run inline — BEFORE the new
        // transaction committed, i.e. eagerly inside an open transaction, the exact bug this
        // class exists to prevent. Now the active transaction wins: the delete parks on the NEW
        // transaction's synchronization and waits for its commit.
        List<String> ran = new ArrayList<>();
        var requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status ->
                TransactionAwareDeletes.afterCommitOrNow(() ->
                        requiresNew.executeWithoutResult(inner -> {
                            TransactionAwareDeletes.afterCommitOrNow(() -> ran.add("inner"));
                            assertTrue(ran.isEmpty(),
                                    "the delete must wait for the REQUIRES_NEW commit, not run inline");
                        })));

        assertEquals(List.of("inner"), ran,
                "once the REQUIRES_NEW transaction committed, its parked delete must have run");
    }

    @Test
    @DisplayName("a REQUIRES_NEW that rolls back inside the callback drops its parked delete")
    void a_requires_new_rollback_inside_the_callback_drops_the_delete() {
        // the payoff of parking on the new transaction: its rollback must take the delete with
        // it — under the old inline shortcut the delete had already run against a transaction
        // that then rolled back
        List<String> ran = new ArrayList<>();
        var requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status ->
                TransactionAwareDeletes.afterCommitOrNow(() ->
                        requiresNew.executeWithoutResult(inner -> {
                            TransactionAwareDeletes.afterCommitOrNow(() -> ran.add("inner"));
                            inner.setRollbackOnly();
                        })));

        assertTrue(ran.isEmpty(),
                "a rolled-back REQUIRES_NEW must not fire the delete parked inside it");
    }

    @Test
    @DisplayName("even an Error from one runnable neither cuts down the others nor escapes tx.execute")
    void a_runnable_throwing_an_error_does_not_block_the_others() {
        // RuntimeException isolation alone is not enough: an Error (OOM while shovelling image
        // bytes, an assertion) thrown by the first synchronization would make Spring stop
        // invoking the REST — including the parked MEME_DELETED publication. After the commit
        // there is no caller left to tell, so the wrapper swallows Throwable and logs.
        List<String> ran = new ArrayList<>();

        assertDoesNotThrow(() -> tx.executeWithoutResult(status -> {
            TransactionAwareDeletes.afterCommitOrNow(() -> {
                ran.add("first");
                throw new AssertionError("catastrophe after commit");
            });
            TransactionAwareDeletes.afterCommitOrNow(() -> ran.add("second"));
        }));

        assertEquals(List.of("first", "second"), ran,
                "the second parked delete must run even though the first one threw an Error");
    }

    @Test
    @DisplayName("the pool hands connections back with autocommit ON — the after-commit sweeps depend on it")
    void the_datasource_keeps_hikaris_autocommit_default() {
        // The after-commit variant sweep (JdbcMemeRepository.deleteById) runs its DELETE on a
        // still-bound transaction connection with autocommit off: it only ever becomes durable
        // because Hikari restores autocommit=true when the connection returns to the pool, which
        // implicitly commits it (spring.datasource.hikari.auto-commit default). Were someone to
        // flip that property, the sweep would roll back silently when the connection returns to
        // the pool — this pin makes such a change fail a test instead.
        //
        // CAVEAT this pin cannot cover: the property is env-overridable at DEPLOY time
        // (SPRING_DATASOURCE_HIKARI_AUTO_COMMIT, or an external config file), where no test
        // runs — an operator setting it to false would bypass this assertion entirely. The
        // deployment manifests must not set it; the k8s README carries that warning.
        assertTrue(dataSource instanceof com.zaxxer.hikari.HikariDataSource,
                "the pool the assumption is about — if this changes, re-audit the after-commit sweeps");
        assertTrue(((com.zaxxer.hikari.HikariDataSource) dataSource).isAutoCommit(),
                "auto-commit must stay true or every after-commit DELETE silently rolls back");
    }
}
