Feature: Closing an account — what becomes of everything the leaver left behind

  A person who asks to be forgotten is owed more than a dead login. The meme they posted, the
  comment they signed and the list of things they saved are held by three different parts of the
  portal, and each has to be dealt with — together, in an order, and reversibly until the last
  moment. This file is the promise the portal makes about that, in the words the promise is made
  in.

  It says nothing about how the parts talk to each other. There is no broker here, no database,
  no HTTP and no container: the scenarios drive the REAL decisions of all four parts — the
  orchestrator and the three participants — wired together in one process. That is deliberate.
  Whether the portal is deployed as six services or one is an answer to a different question, and
  this promise has to hold either way; a spec that could only be run one way would have quietly
  made the choice for us.

  # Each part also has this story from its own side, in its own repo's specs/, and the live stack
  # proves the happy path end to end in e2e/features. What can only be told HERE is what the parts
  # do to each other: the order, the waiting, and what happens when one of them says nothing.

  Background:
    Given alice@example.com posted 2 memes, wrote 3 comments and saved 4 favourites

  Rule: Answering the portal hides everything and destroys nothing

    The first command every part receives is reversible on purpose. Until the last part has
    answered, the case can still fail — and a part that had already destroyed its share would have
    nothing to give back.

    # There is no moment when every part has answered and nothing has been destroyed: the last
    # answer IS the closure. So the reversibility is stated where it can be observed — while one
    # part still owes its answer.
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

    A deletion the portal cannot finish is not left half-done and it is not quietly forgotten: the
    account is handed back, and handed back WITH its content in it.

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

    The right to erasure is exercised, not negotiated, so a request the OWNER made destroys
    everything — including anything the request itself asked to keep. There is no exception for
    content the community happens to like.

    Example: a self-closure carrying conditions destroys anyway
      When security announces that alice@example.com asked to be forgotten, choosing comments=ANONYMIZE_AUTHOR
      And every part of the portal answers
      Then the portal holds nothing of alice@example.com

  Rule: An administrator's closure is a business decision, so its conditions are honoured

    And they are honoured PER PART, which is the reason this rule cannot be stated in any one
    repository: the same closure keeps the comments and destroys the memes.

    Example: the words stay in the thread, signed by nobody
      When an administrator closes alice@example.com, choosing comments=ANONYMIZE_AUTHOR
      And every part of the portal answers
      Then the portal holds 3 comments signed by nobody
      And the portal holds no memes and no favourites of alice@example.com

  Rule: A saved reference has no conditions to honour

    A favourite is a pointer at somebody else's meme. There is nothing in it to anonymise and
    nothing about it to keep for its popularity, so this part reads no conditions at all.

    Example: conditions stated for the favourites part change nothing
      When an administrator closes alice@example.com, choosing collections=ANONYMIZE_AUTHOR
      And every part of the portal answers
      Then the portal holds no favourites of alice@example.com
