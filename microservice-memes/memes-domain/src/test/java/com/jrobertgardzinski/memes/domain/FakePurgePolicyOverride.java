package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.purge.PurgeRule;

import java.util.Optional;

/**
 * An in-memory {@link PurgePolicyOverride}: the admin's dial, held in a field.
 *
 * <p>Public, and this module's own test-jar publishes it, because the dial is one THIRD of a rule
 * that is resolved in three places — the leaver's stated choice, then this, then the deployment
 * default — and no consumer can state that order without being able to turn it. A mock answers
 * {@code current()} with an empty {@link Optional} by default, which pins every scenario to "no
 * override is set" while looking like a choice nobody made.
 */
public class FakePurgePolicyOverride implements PurgePolicyOverride {

    private Optional<PurgeRule> inForce = Optional.empty();

    @Override
    public Optional<PurgeRule> current() {
        return inForce;
    }

    @Override
    public void set(PurgeRule rule, String updatedBy) {
        inForce = Optional.of(rule);
    }

    @Override
    public void clear(String clearedBy) {
        inForce = Optional.empty();
    }
}
