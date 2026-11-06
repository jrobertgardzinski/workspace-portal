Feature: Flagging a MEME as NSFW

  Not every MEME is fit for every screen. A MODERATOR may flag a MEME NSFW and
  take the flag back; the gallery carries the flag so every reader can decide
  what to show. The safe-for-work judgement is a MODERATOR's alone — even the
  author does not get to grade their own homework.

  Background:
    Given a MEME uploaded by its author

  Rule: The NSFW flag is a MODERATOR's dial, and the gallery carries it

    Example:
      When a MODERATOR flags it NSFW
      Then the gallery lists the MEME as NSFW
      When a MODERATOR takes the NSFW flag back
      Then the gallery lists the MEME as safe

  Rule: The safe-for-work judgement is a MODERATOR's alone

    Example:
      When the author tries to flag it NSFW
      Then the flagging is refused as not-a-MODERATOR
      And the gallery lists the MEME as safe
