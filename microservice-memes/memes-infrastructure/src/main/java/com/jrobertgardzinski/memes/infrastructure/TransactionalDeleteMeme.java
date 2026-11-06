package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.system.DeleteMeme;
import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transactional seam around {@link DeleteMeme}: the use case stays framework-free and lists
 * WHAT a teardown is; this decorator (wired instead of the plain bean in {@link MemesConfig})
 * makes the database part of it atomic — votes, content-index claim, tags and the meme row go in
 * ONE transaction, so a crash mid-teardown can no longer leave a half-deleted meme (votes gone,
 * row still served). The ObjectStore may be S3 or a filesystem, which no DB transaction can cover
 * — those adapters defer their deletes to after-commit instead (see their {@code delete}).
 */
class TransactionalDeleteMeme extends DeleteMeme {

    private final TransactionTemplate tx;

    TransactionalDeleteMeme(MemeRepository memes, VoteRepository votes, MemeContentIndex contentIndex,
                            TagRepository tags, MemeEvents memeEvents, TransactionTemplate tx) {
        super(memes, votes, contentIndex, tags, memeEvents);
        this.tx = tx;
    }

    @Override
    public Result execute(String memeId) {
        return tx.execute(status -> super.execute(memeId));
    }
}
