package com.jrobertgardzinski.purge;

import java.util.Optional;

/**
 * The rule a closure states for ONE axis, read once — and, when it is not honoured, the sentence
 * saying so.
 *
 * <p>Two participants had this reading word for word, differing in exactly one word of one log line
 * (the axis: "memes" or "comments"), which is how a rule that is not quite the same on both sides
 * of a saga comes about. It is here rather than in {@code account-closure} because it is the
 * PORTAL's vocabulary: the saga library knows a closure carries conditions and deliberately does
 * not know what they say, and a third participant reads none of this at all — a saved reference is
 * a pointer at somebody else's content, with no author to anonymise.
 *
 * <p>Three answers, and the difference between the last two matters:
 * <ul>
 *   <li>{@code DELETE} stated by this class itself — a self-closure exercises the right to erasure,
 *       and the exceptions to that right are enumerated by law: "this one is popular" is not among
 *       them. Nothing the command carries, and no deployment default, may keep the content.</li>
 *   <li>a rule, when an administrator's closure states one that parses;</li>
 *   <li>EMPTY, which is not "delete" — it leaves the deployment its own default, and is also what
 *       an unreadable rule falls back to, because an operator's typo must not be read as consent to
 *       destroy more than was asked.</li>
 * </ul>
 *
 * @param rule      the rule to apply, or empty to leave the deployment its default
 * @param complaint what the caller should warn about, if anything — never carrying the rule's own
 *                  text, which arrives from the leaver's wizard (see {@link PurgeRule#parse})
 */
public record RequestedRule(Optional<PurgeRule> rule, Optional<String> complaint) {

    /**
     * @param allowsConditions whether the closure's initiator lets conditions count at all
     * @param stated           the rule the command carried for this axis, if any
     * @param axis             this axis's own word for itself, for the log line and nothing else
     */
    public static RequestedRule of(boolean allowsConditions, Optional<String> stated, String axis) {
        if (!allowsConditions) {
            return new RequestedRule(Optional.of(new PurgeRule.Delete()),
                    stated.map(ignored -> "a self-requested closure arrived carrying a " + axis
                            + " purge rule; ignoring it and deleting"));
        }
        if (stated.isEmpty()) {
            return new RequestedRule(Optional.empty(), Optional.empty());
        }
        try {
            return new RequestedRule(Optional.of(PurgeRule.parse(stated.get())), Optional.empty());
        } catch (IllegalArgumentException invalid) {
            return new RequestedRule(Optional.empty(),
                    Optional.of("ignoring an unparseable " + axis + " purge rule, using the "
                            + "default: " + invalid.getMessage()));
        }
    }
}
