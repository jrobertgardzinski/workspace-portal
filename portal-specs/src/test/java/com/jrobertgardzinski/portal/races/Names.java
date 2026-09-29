package com.jrobertgardzinski.portal.races;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the ids a report is full of into words, so the report can be read — and so that reading it
 * twice gives the same text.
 *
 * <p>Two different problems, one answer. The content ids are derived from their names
 * ({@code ContentIds}) and therefore stable, but nobody can see "the leaver's first meme" in
 * {@code e94f46ab-…}. A saga's id is minted at random on every build, so a report carrying one is
 * different text every run and could never be approved and diffed.
 *
 * <p>Known ids become their names; every other UUID becomes {@code <saga-1>}, {@code <saga-2>} …
 * in order of first appearance, which is deterministic as long as the search is.
 */
public final class Names {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Map<String, String> known;

    public Names(Map<String, String> known) {
        this.known = Map.copyOf(known);
    }

    public String read(String text) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Matcher found = UUID.matcher(text);
        StringBuilder readable = new StringBuilder();
        while (found.find()) {
            String id = found.group();
            String name = known.get(id);
            if (name == null) {
                name = aliases.computeIfAbsent(id, unknown -> "<saga-" + (aliases.size() + 1) + ">");
            }
            found.appendReplacement(readable, Matcher.quoteReplacement(name));
        }
        found.appendTail(readable);
        return readable.toString();
    }
}
