package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeErasure;
import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.PurgePolicyOverride;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.purge.PurgeRule;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * The transactional seam around {@link PurgeUserContent} — same reasoning as
 * {@link TransactionalDeleteMeme}: a GDPR purge that dies halfway must not leave some of the
 * leaver's memes deleted and others still under their name; one transaction over the whole DB
 * part makes the purge all-or-nothing (and the saga redelivers the command after a rollback —
 * the use case is idempotent). Blob deletion on S3/filesystem happens after commit, in the
 * store adapters.
 */
class TransactionalPurgeUserContent extends PurgeUserContent {

    private final TransactionTemplate tx;

    TransactionalPurgeUserContent(MemeRepository memeRepository, MemeErasure erasure,
                                  VoteRepository voteRepository, MemeContentIndex contentIndex,
                                  TagRepository tagRepository, MemeEvents memeEvents,
                                  PurgePolicyOverride override, PurgeRule defaultRule,
                                  TransactionTemplate tx) {
        super(memeRepository, erasure, voteRepository, contentIndex, tagRepository, memeEvents,
                override, defaultRule);
        this.tx = tx;
    }

    @Override
    public void execute(com.jrobertgardzinski.identity.UserId author, Optional<PurgeRule> requested) {
        tx.executeWithoutResult(status -> super.execute(author, requested));
    }
}
