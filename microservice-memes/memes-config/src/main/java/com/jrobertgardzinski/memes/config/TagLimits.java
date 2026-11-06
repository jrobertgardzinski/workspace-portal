package com.jrobertgardzinski.memes.config;

/**
 * Server policy on tagging: how many tags one meme may carry (folksonomy, not keyword spam).
 *
 * <p>Zero is legal and means "nobody tags anything" — unlike its siblings here, this dial has a
 * meaningful off position, because a gallery without tags is still a gallery. A NEGATIVE ceiling has
 * no reading at all, and silence about it is worse than a refusal to start: the comparison it ends
 * up in ({@code tags.size() > maxPerMeme}) is then true for every tagging attempt, including the
 * empty one, so a mistyped property turns into "TOO_MANY_TAGS" on a request carrying no tags.
 */
public record TagLimits(int maxPerMeme) {

    public TagLimits {
        if (maxPerMeme < 0) {
            throw new IllegalArgumentException(
                    "maxPerMeme cannot be negative (0 disables tagging), was " + maxPerMeme);
        }
    }
}
