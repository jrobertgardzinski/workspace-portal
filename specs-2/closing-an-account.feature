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
  # The closure has STATES, and this file names them: HIDING while the parts hide their shares,
  # DESTROYING once the account is gone, RESTORING when it stayed, DONE or WITHDRAWN when the parts
  # have finished. Which report moves the closure from one to the next, and which is refused, is
  # the runner's own file — ../account-closure-2/src/test/resources/closure-protocol.feature — and this
  # one takes the states as given and says what is true of the CONTENT in each. "Given the closure
  # has reached DESTROYING" is every part having hidden its share and the account having gone,
  # played out in full through the real use cases; it is a shortcut for the reader, not for the code.
  #
  # How the parts come to know any of this is still not stated. "When memes has hidden alice's
  # content" means that piece of work happened and was reported, and says nothing about whether it
  # was announced, commanded or simply called. What carries a report, how long the closure waits
  # for a part and who gives up on it are the decisions this file is written before.

  Background:
    Given alice has 2 memes, 3 comments and 4 saved references
    And bob saved one of alice's memes

  Rule: The account goes last, and nothing is destroyed before it

    Scenario: while the parts are hiding, the account stays and nothing is destroyed
      Given the closure of alice's account has reached HIDING
      When memes has hidden alice's content
      And comments has hidden alice's content
      Then alice is still in the user repository
      And alice's memes are out of sight
      And alice's comments are out of sight
      But alice's saved references are still in her list
      And the portal still holds 2 memes, 3 comments and 4 references of hers

    Scenario: the last part to hide its share is what lets the account go
      Given the closure of alice's account has reached HIDING
      And memes has hidden alice's content
      And comments has hidden alice's content
      When collections has hidden alice's content
      Then the closure is DESTROYING
      And alice is gone from the user repository
      And alice is gone from the session, factor and recovery-code repositories
      But the portal still holds 2 memes, 3 comments and 4 references of hers

    Scenario: once the account is gone, the parts destroy what they hid
      Given the closure of alice's account has reached DESTROYING
      When memes has destroyed alice's content
      And comments has destroyed alice's content
      And collections has destroyed alice's content
      Then the closure is DONE
      And nothing of alice is left anywhere

  Rule: Alice's meme going costs bob a row, and that is a different matter from alice leaving

    Bob is not leaving. He saved one of alice's memes, and when that meme is destroyed his pointer
    at it has to go — not because of the closure, but because the thing it points at stopped
    existing. So the part that holds saved references is in this chain TWICE, for two unrelated
    reasons, and the two are separate steps.

    Scenario: the stranger's pointer goes with the meme, not with the account
      Given the closure of alice's account has reached DESTROYING
      When memes has destroyed alice's content
      Then bob still has that meme saved
      When collections has heard that alice's meme is gone
      Then bob's reference to it is gone
      But bob is still in the user repository

  Rule: If the account cannot go, everything that was hidden comes back

    This is the sentence the whole order exists for. The account cannot be deleted, so nothing may
    be destroyed and the gallery, the threads and the list are as they were.

    Scenario: the account refuses to go, and the closure turns back
      Given alice's account cannot be deleted
      And the closure of alice's account has reached HIDING
      And memes has hidden alice's content
      And comments has hidden alice's content
      When collections has hidden alice's content
      Then the closure is RESTORING
      And alice is still in the user repository
      And the portal still holds 2 memes, 3 comments and 4 references of hers
      But alice's memes are out of sight

    Scenario: every part brings back what it hid, and alice is as she was
      Given alice's account cannot be deleted
      And the closure of alice's account has reached RESTORING
      When memes has brought alice's content back
      Then alice's memes are back in the gallery
      When comments has brought alice's content back
      Then alice's comments are back in their threads
      When collections has brought alice's content back
      Then alice's saved references are back in her list
      And the closure is WITHDRAWN

    Scenario: the closure is given up on, and only what was hidden has to come back
      Given the closure of alice's account has reached HIDING
      And memes has hidden alice's content
      And comments has hidden alice's content
      When the closure is given up on
      Then the closure is RESTORING
      And alice is still in the user repository
      When memes has brought alice's content back
      And comments has brought alice's content back
      Then the closure is WITHDRAWN
      And alice's memes are back in the gallery
      And alice's comments are back in their threads

  Rule: Once the account is gone it does not come back, whatever happens to the content afterwards

    The one exception to the second sentence, and it is not a symmetry anybody can restore: what
    the account held were secrets — a password hash, a factor's seed, recovery codes — and bringing
    those back under a freed address is worse than leaving content hidden.

    # WHAT FINALLY DESTROYS THE CONTENT IS A HOLE IN THIS FILE. The part that did not destroy its
    # share is sitting on rows nobody can see and nobody will ever come back for: the account they
    # belonged to is gone, so there is nothing left to ask about them. The closure stays DESTROYING
    # for as long as that part keeps quiet, and today one thing in the portal only counts it.
    Scenario: the account is gone and one part has not destroyed its share
      Given the closure of alice's account has reached DESTROYING
      When memes has destroyed alice's content
      And comments has destroyed alice's content
      Then the closure is still DESTROYING
      And alice is gone from the user repository
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
      Given the closure of alice's account has reached DESTROYING
      Then nothing in the portal can be found by alice's address any more
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
      And an administrator closes alice's account, keeping the comments at least 2 readers kept
      And the closure has reached DESTROYING
      When comments has destroyed alice's content
      Then alice's first comment is still in its thread, signed by nobody
      And bob still has that comment saved
      But alice's other comments are gone

    Scenario: alice's own closure ignores the same condition
      Given 3 people upvoted alice's first comment
      And the closure of alice's account is requested, stating that popular comments be kept
      And the closure has reached DESTROYING
      When comments has destroyed alice's content
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
      Given the closure of alice's account has reached HIDING
      And memes has hidden alice's content
      And alice posts one more meme
      And the closure has reached DESTROYING
      When memes has destroyed alice's content
      Then the meme alice posted after that is still in the gallery
      But her first two memes are gone

    Scenario: a reference saved after the references were hidden stays, and is counted
      Given the closure of alice's account has reached DESTROYING
      And alice saves one more reference
      When collections has destroyed alice's content
      Then the reference alice saved after that is still in her list
      And collections had to leave 1 reference behind

    # The author asked for something that LOOKS gone and is not, and was told it does not exist. If
    # the closure then comes back, so does the meme — the one she had just tried to delete herself.
    Scenario: alice takes down a meme the closure has already hidden
      Given the closure of alice's account has reached HIDING
      And memes has hidden alice's content
      When alice takes her first meme down
      Then alice is told there is no such meme
      When the closure is given up on
      And memes has brought alice's content back
      Then alice's memes are back in the gallery
