package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.memes.system.DeleteMeme;
import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.system.RestoreUserContent;
import com.jrobertgardzinski.memes.domain.FakeMemeErasure;
import com.jrobertgardzinski.memes.domain.FakePurgePolicyOverride;
import com.jrobertgardzinski.memes.domain.FakeVoteRepository;
import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.PurgePolicyOverride;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.tags.Tag;
import com.jrobertgardzinski.voting.VoteDirection;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The teeth of workspace ADR 0006 for the gallery: every command is idempotent BY DEFAULT —
 * running it twice must leave exactly the state one run leaves. One generic test enforces
 * the law; the one command that CANNOT obey it (casting a vote — the second identical vote
 * TOGGLES the first away, by UX design) is a DECLARED EXCEPTION with its own proof below.
 */
@Epic("Use case")
@Feature("Idempotent commands")
class IdempotentCommandsTest {

    private static final UserId ALICE = UserId.random();
    private static final UserId BOB = UserId.random();

    /** A fresh gallery per run: two memes, votes, a tag, a flag. */
    private static final class World {
        final Map<String, Meme> memes = new HashMap<>();
        final Map<String, Map<String, VoteDirection>> votes = new HashMap<>();
        final Map<String, String> contentIndex = new HashMap<>();
        final Map<String, Set<Tag>> tags = new HashMap<>();
        final Map<String, Boolean> nsfw = new HashMap<>();
        final List<String> announced = new ArrayList<>();

        final MemeRepository memeRepository = new MemeRepository() {
            public void save(Meme meme) { memes.put(meme.id(), meme); }
            public Optional<Meme> find(String id) { return Optional.ofNullable(memes.get(id)); }
            public List<String> allIds() { return List.copyOf(memes.keySet()); }
            public void deleteById(String memeId) { memes.remove(memeId); }
            public void anonymise(String memeId) {
                memes.computeIfPresent(memeId,
                        (id, m) -> new Meme(m.id(), Optional.empty(), m.format(), m.data()));
            }
        };
        final VoteRepository voteRepository = new FakeVoteRepository(votes);
        final MemeContentIndex index = new MemeContentIndex() {
            public String claim(byte[] data, String candidateId) {
                String earlier = contentIndex.putIfAbsent(new String(data), candidateId);
                return earlier != null ? earlier : candidateId;
            }
            public void remove(String memeId) { contentIndex.values().removeIf(memeId::equals); }
        };
        final TagRepository tagRepository = new TagRepository() {
            public void replaceTags(String memeId, Set<Tag> newTags) {
                tags.put(memeId, new HashSet<>(newTags));
            }
            public Set<Tag> tagsOf(String memeId) { return tags.getOrDefault(memeId, Set.of()); }
            public Set<String> memesTagged(Tag tag) {
                Set<String> hits = new HashSet<>();
                tags.forEach((id, set) -> { if (set.contains(tag)) hits.add(id); });
                return hits;
            }
            public void removeMeme(String memeId) { tags.remove(memeId); }
        };
        final ContentFlags flags = new ContentFlags() {
            public void setNsfw(String memeId, boolean value) { nsfw.put(memeId, value); }
            public boolean isNsfw(String memeId) { return nsfw.getOrDefault(memeId, false); }
            public Set<String> nsfwIds() {
                Set<String> hits = new HashSet<>();
                nsfw.forEach((id, v) -> { if (v) hits.add(id); });
                return hits;
            }
        };
        final MemeEvents memeEvents = announced::add;
        final FakeMemeErasure erasure = new FakeMemeErasure(memes);

        World() {
            memes.put("m1", new Meme("m1", ALICE, "png", new byte[]{1}));
            memes.put("m2", new Meme("m2", BOB, "png", new byte[]{2}));
            contentIndex.put(new String(new byte[]{1}), "m1");
            contentIndex.put(new String(new byte[]{2}), "m2");
            votes.put("m1", new HashMap<>(Map.of("bob@example.com", VoteDirection.UP)));
            tags.put("m1", new HashSet<>(Set.of(new Tag("cats"))));
        }

        Map<String, Object> fingerprint() {
            Map<String, Object> f = new LinkedHashMap<>();
            // Meme is a record over byte[] — array equality is identity, so flatten to text
            f.put("memes", memes.values().stream()
                    .map(m -> m.id() + "|" + m.authorId().map(Object::toString).orElse("-")
                            + "|" + m.format() + "|"
                            + java.util.Arrays.toString(m.data()))
                    .sorted().toList());
            f.put("votes", votes.entrySet().stream().collect(LinkedHashMap::new,
                    (m, e) -> m.put(e.getKey(), Map.copyOf(e.getValue())), Map::putAll));
            f.put("index", Map.copyOf(contentIndex));
            f.put("tags", Map.copyOf(tags));
            f.put("nsfw", Map.copyOf(nsfw));
            f.put("announced", List.copyOf(announced));
            // the saga's reservations are state like any other: a mark that a redelivered command
            // moved (a fresh timestamp on an old obligation) is exactly the kind of drift ADR 0006
            // forbids, and it would be invisible without this line
            f.put("marks", erasure.marks());
            return f;
        }
    }

    private static final PurgePolicyOverride NO_OVERRIDE = new FakePurgePolicyOverride();

    /** A stopped clock: two runs of the same command must not differ by when they ran. */
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            java.time.Instant.parse("2026-08-08T10:00:00Z"), java.time.ZoneOffset.UTC);

    private static final Map<String, Consumer<World>> COMMANDS = commands();

    private static Map<String, Consumer<World>> commands() {
        Map<String, Consumer<World>> c = new LinkedHashMap<>();
        c.put("delete a meme", w -> new DeleteMeme(w.memeRepository, w.voteRepository,
                w.index, w.tagRepository, w.memeEvents).execute("m1"));
        c.put("delete a meme that is not there", w -> new DeleteMeme(w.memeRepository,
                w.voteRepository, w.index, w.tagRepository, w.memeEvents).execute("ghost"));
        c.put("flag a meme NSFW (moderator)", w -> new FlagMeme(w.memeRepository, w.flags)
                .execute("m2", true, true));
        c.put("mark a leaver's gallery for erasure",
                w -> new MarkUserContentForErasure(w.erasure, CLOCK).execute(ALICE));
        c.put("compensate: mark, then restore",
                w -> {
                    new MarkUserContentForErasure(w.erasure, CLOCK).execute(ALICE);
                    new RestoreUserContent(w.erasure).execute(ALICE);
                });
        c.put("purge a leaver's gallery (default rule)",
                w -> {
                    // the whole saga, both phases: the closure erases what the mark reserved
                    new MarkUserContentForErasure(w.erasure, CLOCK).execute(ALICE);
                    new PurgeUserContent(w.memeRepository, w.erasure, w.voteRepository, w.index,
                            w.tagRepository, w.memeEvents, NO_OVERRIDE,
                            new PurgeRule.AnonymizeAuthor())
                            .execute(ALICE, Optional.empty());
                });
        return c;
    }

    @TestFactory
    Stream<DynamicTest> every_command_twice_equals_once() {
        return COMMANDS.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    World once = new World();
                    entry.getValue().accept(once);
                    World twice = new World();
                    entry.getValue().accept(twice);
                    entry.getValue().accept(twice);
                    assertEquals(once.fingerprint(), twice.fingerprint(),
                            "ADR 0006: a command run twice must leave the state of one run");
                }));
    }

    @Test
    @DisplayName("DECLARED EXCEPTION: the same vote twice TOGGLES — two calls undo, by design")
    void cast_vote_is_the_declared_exception() {
        World once = new World();
        new CastVote(once.memeRepository, once.voteRepository)
                .execute("m2", "carol@example.com", VoteDirection.UP);
        World twice = new World();
        CastVote castTwice = new CastVote(twice.memeRepository, twice.voteRepository);
        castTwice.execute("m2", "carol@example.com", VoteDirection.UP);
        castTwice.execute("m2", "carol@example.com", VoteDirection.UP);
        assertNotEquals(once.votes.getOrDefault("m2", Map.of()),
                twice.votes.getOrDefault("m2", Map.of()),
                "the exception the ADR names, proven: a toggle is not idempotent");
    }
}
