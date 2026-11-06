package com.jrobertgardzinski.memes.system;

import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeErasure;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.PurgePolicyOverride;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.memes.domain.MemeMetadata;

import java.util.Optional;

/**
 * The IRREVERSIBLE half of an account deletion, and the reason the saga has a pivot. It runs on the
 * orchestrator's closure command — after every participant has confirmed its reversible mark, so
 * after the last moment at which anything could still go wrong for a reason this service would have
 * to undo. It acts on exactly the memes this service reserved
 * ({@link MemeErasure#pendingOf(UserId)}), never on "everything by that author": a meme uploaded
 * after the mark belongs to no saga and is not the closure's business.
 *
 * <p>The {@link PurgeRule} decides each reserved meme's fate by its score — delete it, or keep it
 * anonymised. Resolution order: the rule carried with the saga command, then the admin's runtime
 * {@link PurgePolicyOverride}, then the deployment default. A meme that goes takes its votes and
 * dedup-index entry along, and a {@code MEME_DELETED} event tells microservice-comments to drop the
 * thread. A meme that stays loses its author and comes BACK to the gallery — the mark was a
 * reservation, and a meme the rule keeps was never going to be erased. Votes the leaver cast are
 * always retracted. Idempotent — the saga may deliver the command twice, and the second delivery
 * finds nothing reserved.
 *
 * <p><strong>THE PIVOT lives inside this use case</strong>, at {@link MemeRepository#deleteById}:
 * that call takes the image out of object storage (MinIO/S3 in the deployed stack), and no message
 * from anywhere can bring it back. Everything before it — the mark, the invisibility, the whole
 * waiting — is undoable; everything from it on can only be RETRIED, which is why the obligation to
 * delete the bytes is itself durable (V9's {@code pending_blob_deletes}) rather than a promise held
 * in one JVM's memory. A saga that reaches this line has therefore already collected every
 * confirmation it will ever need.
 *
 * <p><strong>An absent rule is silence, not a choice.</strong> {@code requested} empty means the
 * command carried no policy at all, and this use case reads that as "decide for me" — which is why
 * the override gets its say next. It does NOT mean the leaver asked for the deployment default: a
 * wizard that wants its own label honoured has to say so, and the memes-ui wizard did not for its
 * preselected option (it sent an empty choice map, so the orchestrator omitted the policy field and
 * an admin's override silently replaced a stated "delete my memes" — P18 poz. 18). The order above
 * therefore honours the leaver over the operator only for a rule that actually arrived here.
 */
public class PurgeUserContent {

    private final MemeRepository memeRepository;
    private final MemeErasure erasure;
    private final VoteRepository voteRepository;
    private final MemeContentIndex contentIndex;
    private final TagRepository tagRepository;
    private final MemeEvents memeEvents;
    private final PurgePolicyOverride override;
    private final PurgeRule defaultRule;

    public PurgeUserContent(MemeRepository memeRepository, MemeErasure erasure,
                            VoteRepository voteRepository, MemeContentIndex contentIndex,
                            TagRepository tagRepository, MemeEvents memeEvents,
                            PurgePolicyOverride override, PurgeRule defaultRule) {
        this.memeRepository = memeRepository;
        this.erasure = erasure;
        this.voteRepository = voteRepository;
        this.contentIndex = contentIndex;
        this.tagRepository = tagRepository;
        this.memeEvents = memeEvents;
        this.override = override;
        this.defaultRule = defaultRule;
    }

    public void execute(UserId author, Optional<PurgeRule> requested) {
        PurgeRule rule = requested.or(override::current).orElse(defaultRule);
        // FIRST, before any score is read: the leaver's own votes are leaving with him anyway, and a
        // rule like "keep what the community liked" must be answered by the COMMUNITY. Retracting
        // them afterwards meant the threshold was measured against a score that no longer existed a
        // moment later — a leaver who had upvoted his own memes bought their survival with a vote
        // this method was about to delete (P18 poz. 39). It is also why the rule is not read at
        // MARK time: the mark must change nothing, and this ordering needs the votes to go first.
        voteRepository.purgeVoter(author.toString());   // ballots are keyed by the voter's id, in its wire form
        for (MemeMetadata meme : erasure.pendingOf(author)) {
            if (rule.keeps(voteRepository.scoreOf(meme.id()))) {
                memeRepository.anonymise(meme.id());
                // and out of the reservation: the community keeps the meme, so it belongs in the
                // gallery again. Leaving it PENDING_ERASURE would hide a meme nobody is erasing and
                // hold it in the backlog alarm for ever.
                erasure.store(meme.restore());
            } else {
                voteRepository.purgeMeme(meme.id());
                contentIndex.remove(meme.id());
                tagRepository.removeMeme(meme.id());
                // >>> THE PIVOT <<< the row goes, and with it the image in object storage. Past
                // this call the saga has nothing to compensate with and nothing to offer but
                // retrying (JdbcMemeRepository.deleteById makes the blob delete durable for exactly
                // that reason). Nothing above this line has destroyed anything.
                memeRepository.deleteById(meme.id());
                memeEvents.memeDeleted(meme.id());
            }
        }
    }
}
