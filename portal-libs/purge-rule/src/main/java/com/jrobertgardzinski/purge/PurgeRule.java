package com.jrobertgardzinski.purge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What happens to a piece of a leaver's content. Three rules: delete it; keep it with the author
 * anonymised; or decide by popularity — content that earned at least {@code minScore} survives
 * anonymised ("the community's, not the author's"), the rest disappears. The textual form
 * ({@code DELETE}, {@code ANONYMIZE_AUTHOR}, {@code KEEP_POPULAR_ANONYMIZED:100}) is the
 * vocabulary shared with callers: deployment env vars, the admin dial and the deletion wizard all
 * speak it.
 *
 * <p>Which rule is the DEFAULT is not this type's business and differs on purpose — the gallery
 * deletes, the comment threads anonymise, because a thread with holes in it is worth less than a
 * gallery with holes in it. That difference is a decision, and the estate's twin-law test
 * deliberately exempts it.
 */
public sealed interface PurgeRule {

    record Delete() implements PurgeRule {}

    record AnonymizeAuthor() implements PurgeRule {}

    record KeepPopularAnonymized(int minScore) implements PurgeRule {
        public KeepPopularAnonymized {
            if (minScore < 1) {
                throw new IllegalArgumentException("minScore must be at least 1, was " + minScore);
            }
        }
    }

    /** The canonical textual form — the inverse of {@link #parse}. */
    default String asText() {
        return switch (this) {
            case Delete() -> "DELETE";
            case AnonymizeAuthor() -> "ANONYMIZE_AUTHOR";
            case KeepPopularAnonymized(int minScore) -> "KEEP_POPULAR_ANONYMIZED:" + minScore;
        };
    }

    /** True when content with this score survives (anonymised) under the rule. */
    default boolean keeps(int score) {
        return switch (this) {
            case Delete() -> false;
            case AnonymizeAuthor() -> true;
            case KeepPopularAnonymized(int minScore) -> score >= minScore;
        };
    }

    /**
     * Read a rule off the wire.
     *
     * <p><strong>The failure carries the SHAPE of what it was given, never the text.</strong> A
     * rule arrives from the account-deletion wizard, so the text is whatever the leaver typed —
     * kilobytes of it, newlines included, i.e. forged lines in an operator's log. Until this type
     * owned the problem, the message pasted that text in and the guard against logging it lived in
     * the two services that happened to catch it: the type handed out a loaded gun and kept the
     * safety catch somewhere else. Now a caller may log {@link Exception#getMessage()} the obvious
     * way and stay safe, which is the only kind of guard that survives a new caller.
     */
    static PurgeRule parse(String text) {
        if ("DELETE".equals(text)) {
            return new Delete();
        }
        if ("ANONYMIZE_AUTHOR".equals(text)) {
            return new AnonymizeAuthor();
        }
        if (text != null && text.startsWith("KEEP_POPULAR_ANONYMIZED:")) {
            try {
                return new KeepPopularAnonymized(
                        Integer.parseInt(text.substring("KEEP_POPULAR_ANONYMIZED:".length())));
            } catch (NumberFormatException invalid) {
                throw notARule(text);
            }
        }
        throw notARule(text);
    }

    private static IllegalArgumentException notARule(String text) {
        return new IllegalArgumentException(text == null
                ? "not a purge rule: nothing at all"
                : "not a purge rule (" + text.length() + " chars, looks like '" + shapeOf(text) + "')");
    }

    /**
     * What a piece of text looked like, in this vocabulary and nothing else — for a log line about
     * a rule that was refused.
     *
     * <p>Whole tokens only. A per-character filter ({@code [A-Z_:0-9]}) keeps every digit and every
     * uppercase letter, which is exactly the alphabet of phone numbers, national id numbers and
     * SHOUTED e-mail addresses, so numeric and uppercase PII sails straight through it. This
     * inverts the burden of proof: only the three rule words survive, and a threshold only in its
     * grammar position after {@code KEEP_POPULAR_ANONYMIZED:} — a free-standing number is NOT
     * vocabulary, because "601 234 567" is a phone number chunked into innocent-looking tokens.
     * Everything unrecognised collapses to one {@code ?} per run, so the log shows whether the
     * text was ALMOST a rule and none of what it held.
     *
     * <p>The four-digit limit is narrower than what {@link #parse} accepts, and deliberately: a
     * popularity threshold is realistically a handful of votes, while ten digits is a telephone.
     * So a five-digit threshold inside a rejected rule shows up as {@code ?}. That asymmetry used
     * to be an accident between two files that could not see each other; here it is a decision on
     * one screen.
     */
    static String shapeOf(String text) {
        if (text == null) {
            return "?";
        }
        StringBuilder kept = new StringBuilder();
        Matcher vocabulary = VOCABULARY.matcher(text);
        int consumedUpTo = 0;
        while (vocabulary.find()) {
            if (vocabulary.start() > consumedUpTo) {
                kept.append('?');   // one ? per unrecognised run, no matter how long or what it held
            }
            kept.append(vocabulary.group());
            consumedUpTo = vocabulary.end();
        }
        if (consumedUpTo < text.length() || text.isEmpty()) {
            kept.append('?');
        }
        return kept.length() <= 32 ? kept.toString() : kept.substring(0, 32) + "…";
    }

    Pattern VOCABULARY = Pattern.compile(
            "(?<![A-Z_0-9:])(?:KEEP_POPULAR_ANONYMIZED(?::\\d{1,4})?|ANONYMIZE_AUTHOR|DELETE)(?![A-Z_0-9:])");
}
