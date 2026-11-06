package com.jrobertgardzinski.memes.closure;

import com.jrobertgardzinski.closure.AtomicClosureParticipant;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureConfirmations;
import com.jrobertgardzinski.closure.ClosureOutcome;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.system.RestoreUserContent;
import com.jrobertgardzinski.memes.domain.Observation;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.purge.RequestedRule;
import com.jrobertgardzinski.unitofwork.UnitOfWork;

/**
 * The meme service's side of the account-closure saga. MARK hides and confirms; ERASE destroys;
 * RESTORE compensates. Only MARK is confirmed, and all three are idempotent.
 *
 * <p>The guard in front of the three commands, the switch between them and the mark's confirmation
 * are {@link AtomicClosureParticipant}'s, shared with the comment service's participant — what is
 * left here is this axis: which use cases run, the rule this axis reads, and what it says about it.
 * Knows nothing about Kafka, so the same class runs in one process.
 */
public final class MemesClosureParticipant extends AtomicClosureParticipant {

    /** This axis's own word for itself, and only for the log line that names it. */
    private static final String AXIS = "memes";

    private final MarkUserContentForErasure markForErasure;
    private final RestoreUserContent restoreUserContent;
    private final PurgeUserContent purgeUserContent;
    private final Observations<Observation> observations;

    public MemesClosureParticipant(MarkUserContentForErasure markForErasure,
                                   RestoreUserContent restoreUserContent,
                                   PurgeUserContent purgeUserContent,
                                   ClosureConfirmations confirmations,
                                   Observations<Observation> observations,
                                   UnitOfWork unitOfWork) {
        super(confirmations, unitOfWork);
        this.markForErasure = markForErasure;
        this.restoreUserContent = restoreUserContent;
        this.purgeUserContent = purgeUserContent;
        this.observations = observations;
    }

    @Override
    protected int mark(String sagaId, UserId leaver) {
        return markForErasure.execute(leaver);
    }

    @Override
    protected void marked(String sagaId, int rows) {
        log.info("marked {} of one leaver's memes for erasure (saga {})", rows, sagaId);
    }

    @Override
    protected ClosureOutcome erase(ClosureCommand command, UserId leaver) {
        RequestedRule requested =
                RequestedRule.of(command.allowsConditions(), command.rule(), AXIS);   // pure reading, kept outside the step
        requested.complaint().ifPresent(log::warn);
        inUnitOfWork(() -> purgeUserContent.execute(leaver, requested.rule()));
        log.info("erased one leaver's marked memes on the saga's closure (saga {})", command.sagaId());
        // this axis's closure reports no numbers: the use case answers nothing, and inventing a
        // count here would be inventing it
        return ClosureOutcome.Erased.uncounted();
    }

    @Override
    protected ClosureOutcome restore(String sagaId, UserId leaver) {
        inUnitOfWork(() -> restoreUserContent.execute(leaver));
        log.info("restored one leaver's marked memes: the saga compensated (saga {})", sagaId);
        return ClosureOutcome.Restored.uncounted();
    }

    @Override
    protected void reservedNothing() {
        observations.record(new Observation.PurgeReservedNothing());
    }
}
