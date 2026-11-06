package com.jrobertgardzinski.memes.system;

import com.jrobertgardzinski.memes.domain.MemeErasure;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.MemeMetadata;


/**
 * The compensation: every meme this service reserved for a leaver goes back into the gallery,
 * exactly as it was. This is what {@link MarkUserContentForErasure} bought — before it, an
 * account-deletion saga that failed at its third participant could apologise but not undo, because
 * the first participant had already shredded the content.
 *
 * <p><strong>The orchestrator decides, not this service.</strong> There is no local timeout here,
 * no "the closure never came, let us assume the worst": a participant that restored on its own
 * clock would put a leaver's memes back in the gallery while the saga was still, correctly, waiting
 * for a slow sibling — and the account it belongs to may by then be gone. The only trigger is the
 * orchestrator's compensation command.
 *
 * <p><strong>Idempotent</strong> (workspace ADR 0006), and deliberately silent about how much it
 * found: a redelivered command restores nothing because the first one already did, and a command
 * for a saga this service never got as far as marking finds nothing at all. Neither is an error —
 * the orchestrator compensates every participant it commanded, and it cannot know which of them
 * heard the first command.
 *
 * <p>Note what it does NOT need to undo: votes, tags, the dedup claim, the blob. The mark touched
 * none of them, which is exactly why it is reversible at all.
 */
public class RestoreUserContent {

    private final MemeErasure erasure;

    public RestoreUserContent(MemeErasure erasure) {
        this.erasure = erasure;
    }

    public void execute(UserId author) {
        for (MemeMetadata meme : erasure.pendingOf(author)) {
            erasure.store(meme.restore());
        }
    }
}
