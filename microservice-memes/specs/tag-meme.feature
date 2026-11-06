Feature: Tagging a MEME and finding it by TAG

  The uploader curates their MEME's TAGS — a small, legal set of search keys,
  not free text. Anyone browses the gallery narrowed to one TAG; the TAGS of a
  purged MEME vanish with it.

  Background:
    Given a USER
    And an uploaded MEME

  Rule: The author's TAGS make the MEME findable — by those TAGS and no others

    Example:
      When the author tags it with "cats" and "monday-mood"
      Then the gallery filtered by "cats" contains that MEME
      And the gallery filtered by "dogs" does not

  Rule: Only the author curates the TAGS

    Example:
      When another USER tries to tag it with "hijack"
      Then the tagging is refused as not-the-author

  Rule: Keyword spam is refused

    Example:
      When the author tags it with "not a tag!" and "monday-mood"
      Then the tagging is refused as an invalid TAG
