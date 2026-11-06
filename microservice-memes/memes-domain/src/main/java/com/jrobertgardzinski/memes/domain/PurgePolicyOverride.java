package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.purge.PurgeRule;

import java.util.Optional;

/**
 * An admin's runtime override of the deployment's default purge rule (the memes axis). The
 * resolution order for a purge is: the leaver's wizard choice (rides with the saga command),
 * then this override, then the env default — so an operator can retune the policy without a
 * redeploy, and a leaver who STATED a rule still outranks the dial.
 *
 * <p>Stated is the whole of it: what arrives here is a rule or nothing, and nothing means "no
 * preference expressed", which this override is then free to answer. That is not the same thing as
 * "the leaver picked what happens to be our default" — the two used to be indistinguishable on the
 * wire, because the wizard sent an empty choice map for its preselected option and the override
 * quietly decided instead (P18 poz. 18). Every option states its rules now; a caller that sends
 * none is asking for the deployment's judgement, not overriding it.
 */
public interface PurgePolicyOverride {

    /** The override currently in force, if an admin has set one. */
    Optional<PurgeRule> current();

    /** Replaces the override; {@code updatedBy} is the admin's identity, kept for the audit. */
    void set(PurgeRule rule, String updatedBy);

    /**
     * Removes the override — the deployment default applies again. {@code clearedBy} is the
     * admin's identity, for the same reason {@link #set}'s is: moving this dial, in either
     * direction, decides the fate of other people's content.
     */
    void clear(String clearedBy);
}
