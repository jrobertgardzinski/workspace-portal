package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.FakeMemeRepository;
import com.jrobertgardzinski.memes.domain.FakePurgePolicyOverride;
import com.jrobertgardzinski.memes.domain.FakeVoteRepository;
import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.system.DeleteMeme;
import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.system.RestoreUserContent;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.voting.VoteDirection;

import java.time.Clock;
import java.util.Optional;

import static org.mockito.Mockito.mock;

/**
 * The gallery: memes-domain's own fake of its ports, and the four memes-system use cases that act
 * on a leaver's content. No adapter, no blob store, no broker — the use cases are the real ones.
 *
 * <p>{@link TagRepository} and {@link MemeContentIndex} are mocks, by the verdict of 2026-09-28:
 * what they hide is one service's own promise, a level below anything this suite states, and this
 * file claims nothing about a tag or about deduplication.
 */
public final class MemesPart {

    private final FakeMemeRepository rows = new FakeMemeRepository();
    private final FakeVoteRepository votes = new FakeVoteRepository();
    private final FakePurgePolicyOverride override = new FakePurgePolicyOverride();

    private final MarkUserContentForErasure hide;
    private final PurgeUserContent destroy;
    private final RestoreUserContent bringBack;
    private final DeleteMeme takeDown;

    MemesPart(Clock clock, MemeAnnouncements announcements) {
        this.hide = new MarkUserContentForErasure(rows, clock);
        this.destroy = new PurgeUserContent(rows, rows, votes, mock(MemeContentIndex.class),
                mock(TagRepository.class), announcements, override, new PurgeRule.Delete());
        this.bringBack = new RestoreUserContent(rows);
        this.takeDown = new DeleteMeme(rows, votes, mock(MemeContentIndex.class),
                mock(TagRepository.class), announcements);
    }

    /** The rows, for the steps to seed and to read. Assertions ask this fake and nothing else. */
    public FakeMemeRepository rows() {
        return rows;
    }

    public FakeVoteRepository votes() {
        return votes;
    }

    public void posted(UserId author, int howMany, String name) {
        for (int i = 1; i <= howMany; i++) {
            rows.posted(ContentIds.memeOf(name, i), author);
        }
    }

    public void upvoted(String memeId, int howManyPeople) {
        for (int i = 1; i <= howManyPeople; i++) {
            votes.cast(memeId, "reader-" + i, VoteDirection.UP);
        }
    }

    public void hide(UserId leaver) {
        hide.execute(leaver);
    }

    public void destroy(UserId leaver, Optional<PurgeRule> condition) {
        destroy.execute(leaver, condition);
    }

    public void bringBack(UserId leaver) {
        bringBack.execute(leaver);
    }

    public DeleteMeme.Result takeDown(String memeId) {
        return takeDown.execute(memeId);
    }
}
