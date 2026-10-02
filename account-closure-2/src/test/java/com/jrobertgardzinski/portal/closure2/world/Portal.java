package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.email.domain.Email;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.security.domain.vo.DeletionInitiator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Everything one scenario has: an account, three parts of the portal holding content, and what the
 * parts told each other. One per scenario — the steps build it in their constructor and nothing is
 * shared between two scenarios.
 *
 * <p>There is no bus here, no router and nothing that waits. The feature file moves the chain along
 * one step at a time, and each step is one part doing its share or one assertion about what is
 * true. What delivers the facts in production is the decision this suite is written before.
 */
public final class Portal {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);

    private final People people = new People();
    private final MemeAnnouncements memeAnnouncements = new MemeAnnouncements();
    private final CommentAnnouncements commentAnnouncements = new CommentAnnouncements();

    private final IdentityPart identity = new IdentityPart(clock);
    private final MemesPart memes = new MemesPart(clock, memeAnnouncements);
    private final CommentsPart comments = new CommentsPart(clock, commentAnnouncements);
    private final CollectionsPart collections = new CollectionsPart(clock);

    /**
     * What an administrator's closure said should happen to one part's content.
     *
     * <p>Stated by a step and read by the destroying step, as a {@link PurgeRule} record — never as
     * text. A closure carries its conditions as opaque strings, and reading those back needs a
     * parser that lives a level above the use cases this suite drives; the rule itself is a public
     * record in the portal's own library, so the suite builds one and hands it over.
     */
    private final Map<String, PurgeRule> conditions = new LinkedHashMap<>();

    private DeletionInitiator initiator = DeletionInitiator.SELF;

    /** Somebody with an account, known by name here, by address to identity, by id to the parts. */
    public UserId arrived(String name) {
        if (!people.knows(name)) {
            people.registered(name, identity.registered(People.emailOf(name)));
        }
        return people.idOf(name);
    }

    public UserId idOf(String name) {
        return people.idOf(name);
    }

    public Email emailOf(String name) {
        return People.emailOf(name);
    }

    public IdentityPart identity() {
        return identity;
    }

    public MemesPart memes() {
        return memes;
    }

    public CommentsPart comments() {
        return comments;
    }

    public CollectionsPart collections() {
        return collections;
    }

    public MemeAnnouncements memeAnnouncements() {
        return memeAnnouncements;
    }

    public CommentAnnouncements commentAnnouncements() {
        return commentAnnouncements;
    }

    public void stated(String part, PurgeRule rule) {
        conditions.put(part, rule);
    }

    public void closedBy(DeletionInitiator who) {
        this.initiator = who;
    }

    public DeletionInitiator initiator() {
        return initiator;
    }

    /**
     * The condition this part is to honour, if any.
     *
     * <p>Empty for an owner's own closure whatever was stated, which is the law and not a
     * preference: the exceptions to the right to erasure are enumerated, and "the readers liked it"
     * is not among them. {@code AccountClosure} enforces the same thing on its own constructor.
     */
    public Optional<PurgeRule> conditionFor(String part) {
        return initiator == DeletionInitiator.ADMIN
                ? Optional.ofNullable(conditions.get(part))
                : Optional.empty();
    }
}
