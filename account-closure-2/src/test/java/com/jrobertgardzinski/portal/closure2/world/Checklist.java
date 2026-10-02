package com.jrobertgardzinski.portal.closure2.world;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Three boxes to tick — one per part of the portal — and what happens when the last one is ticked.
 *
 * <p><strong>This is a stand-in for this suite, not a decision about the architecture.</strong>
 * Something in the portal has to notice that every part has hidden its share and only then delete
 * the account; today that something is {@code AccountDeletionOrchestrator} in
 * {@code security-infrastructure}, with its quorum, its latch, its retries and its timeout, and
 * the feature file deliberately says nothing about it because that is exactly what has not been
 * decided. The list below is the least that can play the role: a set of names and one callback.
 * In a single-process portal the same role is played by the transaction's commit, for free.
 *
 * <p>What it does hold is the ORDER the feature promises: nothing is destroyed before the account
 * is gone ({@link #verdict()} gates every destroying step), and if the account cannot go — the row
 * refuses, or the list is given up on — the verdict is that everything comes back.
 */
public final class Checklist {

    public enum Verdict {
        /** Not every part has answered yet. Nothing may be destroyed; nothing has to come back. */
        OPEN,
        /** The account is gone. The parts may destroy; nothing comes back, ever. */
        ACCOUNT_GONE,
        /** The account stays. The parts bring back what they hid; nothing may be destroyed. */
        ACCOUNT_STAYS
    }

    public static final Set<String> PARTS = Set.of("memes", "comments", "collections");

    private final Set<String> hidden = new LinkedHashSet<>();
    private final Runnable deleteTheAccount;
    private final Runnable unlockTheAccount;
    private Verdict verdict = Verdict.OPEN;

    Checklist(Runnable deleteTheAccount, Runnable unlockTheAccount) {
        this.deleteTheAccount = deleteTheAccount;
        this.unlockTheAccount = unlockTheAccount;
    }

    /** One part reports its share hidden. The third report is what deletes the account. */
    public void hiddenBy(String part) {
        if (!PARTS.contains(part)) {
            throw new IllegalArgumentException("no such part of the portal: " + part);
        }
        if (verdict != Verdict.OPEN) {
            throw new IllegalStateException(part + " reports after the case was closed as " + verdict);
        }
        hidden.add(part);
        if (hidden.containsAll(PARTS)) {
            try {
                deleteTheAccount.run();
                verdict = Verdict.ACCOUNT_GONE;
            } catch (RuntimeException refused) {
                // the row would not go: the lock comes off and everything hidden has to come back
                verdict = Verdict.ACCOUNT_STAYS;
                unlockTheAccount.run();
            }
        }
    }

    /** The list is waited out: whoever has not answered never will, and the account stays. */
    public void giveUp() {
        if (verdict == Verdict.OPEN) {
            verdict = Verdict.ACCOUNT_STAYS;
            unlockTheAccount.run();
        }
    }

    public boolean heardFrom(String part) {
        return hidden.contains(part);
    }

    public Verdict verdict() {
        return verdict;
    }
}
