Feature: Closing an account — what becomes of everything the leaver left behind

  A person who asks to be forgotten is owed more than a dead login. Their meme, their comment and
  their saved list are held by three different parts of the portal, and the six rules below are
  what the portal promises about all three at once.

  # No broker, no database, no HTTP, no container: the REAL orchestrator and the REAL three
  # participants, in one process. Whether the portal ships as six services or one is a separate
  # question, and these promises hold either way. Details in ../specs/README.md.

  Background:
    Given alice@example.com posted 2 memes, wrote 3 comments and saved 4 favourites

  Rule: Answering the portal hides everything and destroys nothing

    A part that had already destroyed its share would have nothing to give back.

    # There is no moment when every part has answered and nothing is destroyed — the last answer
    # IS the closure — so this is stated while one part still owes its answer.
    Example: two parts have answered and the third has not
      When security announces that alice@example.com asked to be forgotten
      And every part except collections answers
      Then their 2 memes and 3 comments are out of sight
      But the portal still holds 2 memes, 3 comments and 4 favourites of theirs

  Rule: Only the closure destroys, and the closure needs every answer

    Example: the last answer closes the case
      When security announces that alice@example.com asked to be forgotten
      And every part of the portal answers
      Then security is told the portal purged the content of alice@example.com
      And the portal holds nothing of alice@example.com

    Example: one part never got its command, so nothing is destroyed
      When security announces that alice@example.com asked to be forgotten
      And every part except memes answers
      Then security is told nothing yet
      And their 3 comments and 4 favourites are out of sight
      But their 2 memes are still in the gallery, because that part never heard
      And the portal still holds 2 memes, 3 comments and 4 favourites of theirs

  Rule: When the portal gives up waiting, everything comes back

    Handed back WITH its content in it, which is the whole difference ADR 0007 bought.

    Example: the silent part is waited out
      When security announces that alice@example.com asked to be forgotten
      And every part except memes answers
      And the portal gives up waiting
      Then security is told the purge of alice@example.com failed
      And alice@example.com sees their 2 memes, 3 comments and 4 favourites again

    Example: the compensation reaches the silent part too, and costs it nothing
      When security announces that alice@example.com asked to be forgotten
      And every part except memes answers
      And the portal gives up waiting
      Then the portal holds 2 memes, 3 comments and 4 favourites of alice@example.com

  Rule: The leaver's own request admits no conditions

    The right to erasure is exercised, not negotiated, and has no exception for content the
    community happens to like — so even conditions the request itself carried are ignored.

    Example: a self-closure carrying conditions destroys anyway
      When security announces that alice@example.com asked to be forgotten, choosing comments=ANONYMIZE_AUTHOR
      And every part of the portal answers
      Then the portal holds nothing of alice@example.com

  Rule: An administrator's closure is a business decision, so its conditions are honoured

    Per part — one closure keeps the comments and destroys the memes, which is precisely why no
    single repository can state this rule.

    Example: the words stay in the thread, signed by nobody
      When an administrator closes alice@example.com, choosing comments=ANONYMIZE_AUTHOR
      And every part of the portal answers
      Then the portal holds 3 comments signed by nobody
      And the portal holds no memes and no favourites of alice@example.com

  Rule: A saved reference has no conditions to honour

    A favourite is a pointer at somebody else's meme: nothing to anonymise, nothing to keep for
    its popularity. This part reads no conditions at all.

    Example: conditions stated for the favourites part change nothing
      When an administrator closes alice@example.com, choosing collections=ANONYMIZE_AUTHOR
      And every part of the portal answers
      Then the portal holds no favourites of alice@example.com
