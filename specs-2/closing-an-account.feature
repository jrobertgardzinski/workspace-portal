Feature: Closing an account — the order of what happens, and what is true in between

  Alice asks to be forgotten. Her memes are in the gallery, her words are in other people's
  threads, her saved list is her own, and her account is what everything else is found by. Four
  parts of the portal hold a piece of her, and the file below says in what order they let go of it
  and what has to be true at every step on the way.

  Two sentences hold the whole thing:
  NOTHING IS DESTROYED UNTIL THE ACCOUNT IS GONE. IF THE ACCOUNT CANNOT GO, EVERYTHING COMES BACK.

  # Content is first HIDDEN and not destroyed, which is what makes the second sentence possible: a
  # part that had already destroyed its share would have nothing to give back. So the account
  # OUTLIVES its content in the gallery and does NOT outlive it in storage — hide everything,
  # remove the account, and only then destroy.
  #
  # How the parts come to know any of this is not stated here, on purpose: this file is written
  # BEFORE that decision. "When memes has hidden alice's content" means that piece of work
  # happened, and says nothing about whether it was announced, commanded or simply called. With one
  # database for the whole portal every sentence below is true for free — hiding is an uncommitted
  # delete and coming back is a rollback — and with the parts in separate processes something has to
  # be built to keep them. Whatever that is has to satisfy this file.
  #
  # WHO REMOVES THE ACCOUNT ONCE EVERY PART HAS HIDDEN ITS SHARE IS A HOLE IN THIS FILE, and the
  # most important one. Something has to notice that all three are done and then delete the
  # account; what that something is, how long it waits and what it does when a part never answers
  # are exactly the questions nobody has decided. This suite ticks three boxes and deletes the
  # account on the third tick, which is the least that can stand in for it.

  Background:
    Given alice has 2 memes, 3 comments and 4 saved references
    And bob saved one of alice's memes

  Rule: The account goes last, and nothing is destroyed before it

    Every part hides its share, the account goes, and only then is anything destroyed. The whole
    chain, in one scenario, because the order IS the promise.

    Scenario: the chain from the request to the last thing destroyed
      When the closure of alice's account is requested
      Then alice is still in the user repository
      When memes has hidden alice's content
      Then alice's memes are out of sight
      But the meme repository still holds them
      And alice is still in the user repository
      When comments has hidden alice's content
      Then alice's comments are out of sight
      But the comment repository still holds them
      And alice is still in the user repository
      When collections has hidden alice's content
      Then alice's saved references are out of sight
      But the collection repository still holds them
      And only now is alice gone from the user repository
      And alice is gone from the session, factor and recovery-code repositories
      But the portal still holds 2 memes, 3 comments and 4 references of hers
      When memes has destroyed alice's content
      Then alice's memes are gone
      When comments has destroyed alice's content
      Then alice's comments are gone
      When collections has destroyed alice's content
      Then alice's saved references are gone
      And nothing of alice is left anywhere

  Rule: Alice's meme going costs bob a row, and that is a different matter from alice leaving

    Bob is not leaving. He saved one of alice's memes, and when that meme is destroyed his pointer
    at it has to go — not because of the closure, but because the thing it points at stopped
    existing. So the part that holds saved references is in this chain TWICE, for two unrelated
    reasons, and the two are separate steps.

    Scenario: the stranger's pointer goes with the meme, not with the account
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And comments has hidden alice's content
      And collections has hidden alice's content
      And memes has destroyed alice's content
      Then bob still has that meme saved
      When collections has heard that alice's meme is gone
      Then bob's reference to it is gone
      But bob is still in the user repository

  Rule: If the account cannot go, everything that was hidden comes back

    This is the sentence the whole order exists for. The account cannot be deleted, so nothing may
    be destroyed and the gallery, the threads and the list are as they were.

    Scenario: the account cannot be deleted
      Given alice's account cannot be deleted
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And comments has hidden alice's content
      And collections has hidden alice's content
      Then alice is still in the user repository
      And the portal still holds 2 memes, 3 comments and 4 references of hers
      When memes has brought alice's content back
      Then alice's memes are back in the gallery
      When comments has brought alice's content back
      Then alice's comments are back in their threads
      When collections has brought alice's content back
      Then alice's saved references are back in her list

  Rule: While one part has not hidden its share, the account stays and nothing is destroyed

    Scenario: one part has not hidden anything
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And comments has hidden alice's content
      Then alice is still in the user repository
      And alice's memes are out of sight
      But alice's saved references are still in her list
      And the portal still holds 2 memes, 3 comments and 4 references of hers

    Scenario: the closure is given up on, and what was hidden comes back
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And comments has hidden alice's content
      And the closure is given up on
      Then alice is still in the user repository
      When memes has brought alice's content back
      Then alice's memes are back in the gallery
      When comments has brought alice's content back
      Then alice's comments are back in their threads

  Rule: Once the account is gone it does not come back, whatever happens to the content afterwards

    The one exception to the second sentence, and it is not a symmetry anybody can restore: what
    the account held were secrets — a password hash, a factor's seed, recovery codes — and bringing
    those back under a freed address is worse than leaving content hidden.

    # WHAT FINALLY DESTROYS THE CONTENT IS A HOLE IN THIS FILE. The part that did not destroy its
    # share is sitting on rows nobody can see and nobody will ever come back for: the account they
    # belonged to is gone, so there is nothing left to ask about them. Today one thing in the portal
    # looks at this and only counts it.
    Scenario: the account is gone and one part has not destroyed its share
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And comments has hidden alice's content
      And collections has hidden alice's content
      And memes has destroyed alice's content
      And comments has destroyed alice's content
      Then alice is gone from the user repository
      And alice's memes are gone
      And alice's comments are gone
      But alice's saved references are out of sight
      And the collection repository still holds them

  Rule: The account is found by its address and the content by an id, and only the account holds both

    The parts of the portal know alice by the id her account was given; identity knows her by her
    address; the account row is the only place the two meet. Destroying happens AFTER the account
    is gone, so by then that row is no longer there to be asked — whoever drives this has to have
    read the id while the account still existed, or there is nothing left to find her content by.

    Scenario: the content is still found after the account that named it is gone
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And comments has hidden alice's content
      And collections has hidden alice's content
      Then alice is gone from the user repository
      And nothing in the portal can be found by alice's address any more
      When memes has destroyed alice's content
      Then alice's memes are gone

  Rule: An administrator's closure may keep what the readers kept; alice's own request may not

    Alice exercising her right to be forgotten is not negotiating, and "the readers liked it" is
    not one of the exceptions the law lists. An administrator closing somebody else's account is
    making an ordinary business decision instead, so its conditions count — and a kept comment
    stays in its thread signed by nobody, which leaves every pointer at it still good.

    Scenario: the words the readers kept stay in the thread, signed by nobody
      Given 3 people upvoted alice's first comment
      And bob saved alice's first comment
      When an administrator closes alice's account, keeping the comments at least 2 readers kept
      And memes has hidden alice's content
      And comments has hidden alice's content
      And collections has hidden alice's content
      And comments has destroyed alice's content
      Then alice's first comment is still in its thread, signed by nobody
      And bob still has that comment saved
      But alice's other comments are gone

    Scenario: alice's own closure ignores the same condition
      Given 3 people upvoted alice's first comment
      When the closure of alice's account is requested, stating that popular comments be kept
      And memes has hidden alice's content
      And comments has hidden alice's content
      And collections has hidden alice's content
      And comments has destroyed alice's content
      Then alice's comments are gone

  Rule: Things that happen DURING a closure are not part of it

    Alice's account is locked the moment the closure is asked for, but a token already in a tab
    keeps being accepted until it expires — so she can still post and still save for up to an hour
    after asking to be forgotten. Whatever arrives in that window was never hidden, so it cannot be
    destroyed: a part may only destroy what it hid.

    # IS THAT A PROMISE OR A HOLE? Content posted during the closure outliving it is at least
    # honest, and the row is at worst a leftover under an address nobody holds any more. It is
    # written down here rather than decided.
    Scenario: a meme posted after the memes were hidden stays
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And alice posts one more meme
      And comments has hidden alice's content
      And collections has hidden alice's content
      And memes has destroyed alice's content
      Then the meme alice posted after that is still in the gallery
      But her first two memes are gone

    Scenario: a reference saved after the references were hidden stays, and is counted
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And comments has hidden alice's content
      And collections has hidden alice's content
      And alice saves one more reference
      And collections has destroyed alice's content
      Then the reference alice saved after that is still in her list
      And collections had to leave 1 reference behind

    # The author asked for something that LOOKS gone and is not, and was told it does not exist. If
    # the closure then comes back, so does the meme — the one she had just tried to delete herself.
    Scenario: alice takes down a meme the closure has already hidden
      When the closure of alice's account is requested
      And memes has hidden alice's content
      And alice takes her first meme down
      Then alice is told there is no such meme
      When the closure is given up on
      And memes has brought alice's content back
      Then alice's memes are back in the gallery
