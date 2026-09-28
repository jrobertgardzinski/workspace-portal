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

  Rule: A popularity condition is answered by the community, and splits each part from within

    KEEP_POPULAR_ANONYMIZED states one threshold for the whole closure, and what meets it is not
    the portal's to decide — the readers already decided, part by part and item by item. So one
    command both keeps and destroys inside the SAME part, which is a sentence no single repository
    can finish: the gallery does not know what the threads kept, and neither of them knows that a
    saved pointer was never in the running.

    Example: what the readers liked survives without its author, the rest goes
      Given 3 people upvoted 1 of alice@example.com's memes
      And 3 people upvoted 1 of alice@example.com's comments
      When an administrator closes alice@example.com, choosing memes=KEEP_POPULAR_ANONYMIZED:2 and comments=KEEP_POPULAR_ANONYMIZED:2
      And every part of the portal answers
      Then the portal holds 1 meme and 1 comment signed by nobody
      And the portal holds nothing of alice@example.com

  Rule: The leaver's memes take other people's threads and pointers with them

    A meme is not only the leaver's row. Its conversation was written by people who are not
    leaving, and the pointers at it are in lists belonging to people who are not leaving either.
    Destroying the leaver's memes destroys all of it — not as a second teardown written into the
    closure, but as the SAME cascade the author's own take-down sets off, started from inside the
    closure's irreversible half. It is the one promise here that no confirmation covers.

    Example: a thread written by other people goes with the meme it hangs under
      Given 3 other people commented under their first meme
      And 2 other people saved their first meme, and 3 saved a comment under it
      When security announces that alice@example.com asked to be forgotten
      And every part of the portal answers
      And the cascade reaches every part
      Then nothing is left under their first meme
      And nobody has their first meme saved any more
      And nobody has a comment of their first meme saved any more

    Example: the count security is given is the leaver's rows, and only those
      Given 3 other people commented under their first meme
      And 2 other people saved their first meme, and 3 saved a comment under it
      When security announces that alice@example.com asked to be forgotten
      And every part of the portal answers
      And the cascade reaches every part
      Then the memes part confirmed 2 reserved
      But 8 rows belonging to other people went too, named in no confirmation

  Rule: The cascade starts after the pivot, so the portal reports done before it has run

    The saga is finished when the last answer lands. What that last answer SET OFF is still in
    the air: a choreography with no orchestrator, no confirmation, nobody waiting and nothing to
    compensate with. This is not a bug to be fixed at this level — it is the shape of the trade,
    and the reason it is written down is that until it was, nobody had said it out loud.

    Example: security is told the content is purged while the cascade is still on the wire
      Given 3 other people commented under their first meme
      When security announces that alice@example.com asked to be forgotten
      And every part of the portal answers
      Then security is told the portal purged the content of alice@example.com
      But the 3 comments under their first meme are still there
      And a cascade nobody is waiting for is still on the wire

  Rule: What the readers keep, keeps its thread

    The popularity condition splits one part from within, and the cascade is what carries that
    split OUT of that part: the meme that stays announces nothing, so its conversation is never
    told to go. One command, one author, two memes, and the thread under one of them survives
    because strangers liked the picture above it.

    Example: one closure, and only the thread of the meme that went
      Given 3 other people commented under their first meme
      And 2 other people commented under their second meme
      And 3 people upvoted 1 of alice@example.com's memes
      When an administrator closes alice@example.com, choosing memes=KEEP_POPULAR_ANONYMIZED:2
      And every part of the portal answers
      And the cascade reaches every part
      Then the 3 comments under their first meme are still there
      But nothing is left under their second meme

  Rule: A cascade can take a row the saga had already reserved, and the saga must survive it

    The two protocols reach the same rows from different directions, and neither holds a lock on
    the other. A stranger deleting a meme of their own takes the whole thread under it, including
    a comment the running closure had counted and promised. The closure does not break — its
    irreversible half acts on what is still reserved, not on the number it sent — but the number
    it sent was already wrong when it was sent, and the compensation cannot put back what the
    other protocol took.

    Example: the thread goes while the saga is still collecting answers
      Given bob@example.com posted a meme and alice@example.com commented under it
      When security announces that alice@example.com asked to be forgotten
      And every part except collections answers
      And bob@example.com takes their meme down
      And the cascade reaches every part
      And collections answers after all
      Then security is told the portal purged the content of alice@example.com
      And the portal holds nothing of alice@example.com
      But the comments part had confirmed 4 reserved, one of which was gone before it was erased

    Example: what the cascade took does not come back when the portal gives up
      Given bob@example.com posted a meme and alice@example.com commented under it
      When security announces that alice@example.com asked to be forgotten
      And every part except memes answers
      And bob@example.com takes their meme down
      And the cascade reaches every part
      And the portal gives up waiting
      Then security is told the purge of alice@example.com failed
      And alice@example.com sees their 2 memes, 3 comments and 4 favourites again
      But the comment they wrote under bob@example.com's meme is not among them

  Rule: A comment the closure destroys is announced, so nobody is left pointing at it

    Whatever the closure destroys, nobody is left holding a pointer at it — for a comment exactly
    as for a meme. The collections part cleans only the leaver's OWN rows, by their user id, so a
    stranger's saved pointer at the leaver's words can be collected in one way only: by being told
    which words went. The closure says so on the deletion cascade's own topic, with the message
    that cascade already carries and the consumer it already has.

    The meme side of this was promised from the start (`meme-deletion.feature`) and the comment
    side was not, which is the whole reason this rule is written down rather than assumed.

    Example: a stranger's saved pointer goes with the comment it points at
      Given bob@example.com posted a meme and alice@example.com commented under it
      And a stranger saved that comment
      When security announces that alice@example.com asked to be forgotten
      And every part of the portal answers
      And the cascade reaches every part
      Then the portal holds nothing of alice@example.com
      And nobody has that comment saved any more

    Example: a comment the readers kept keeps the pointers at it
      Given bob@example.com posted a meme and alice@example.com commented under it
      And a stranger saved that comment
      And 3 people upvoted that comment
      When an administrator closes alice@example.com, choosing comments=KEEP_POPULAR_ANONYMIZED:2
      And every part of the portal answers
      And the cascade reaches every part
      Then the stranger still has that comment saved
      But the portal holds no memes and no favourites of alice@example.com

  Rule: A saved reference has no conditions to honour

    A favourite is a pointer at somebody else's meme: nothing to anonymise, nothing to keep for
    its popularity. This part reads no conditions at all.

    Example: conditions stated for the favourites part change nothing
      When an administrator closes alice@example.com, choosing collections=ANONYMIZE_AUTHOR
      And every part of the portal answers
      Then the portal holds no favourites of alice@example.com
