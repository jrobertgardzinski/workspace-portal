Feature: Deleting a meme — what goes with it, and what is left behind

  A meme is never alone. Its conversation lives in one part of the portal and the lists people
  saved it to live in another, and when the meme goes those have to go too — or the portal shows
  its readers things that are not there any more.

  # No broker, no database, no HTTP, no container: the REAL teardown and the REAL two hops, in
  # one process. Nobody orchestrates this one — each part reacts to the part before it and
  # nothing confirms — which is why the last rule below can say what it says. Details in
  # ../specs/README.md.

  Background:
    Given bob@example.com posted the meme "the cat one"

  Rule: What the meme took with it, nobody can still be pointing at

    Example: an author takes their meme down
      Given 3 people commented on "the cat one"
      And 2 people saved "the cat one" and 3 saved comments under it
      When bob@example.com takes "the cat one" down
      And every part of the portal hears it
      Then "the cat one" has no comments left
      And nobody has "the cat one" saved
      And nobody has a comment of "the cat one" saved

    Example: a meme nobody commented on
      Given 2 people saved "the cat one"
      When bob@example.com takes "the cat one" down
      And every part of the portal hears it
      Then nobody has "the cat one" saved
      And nothing was announced about the comments of "the cat one"

  Rule: The same deletion twice changes nothing and announces nothing

    The broker delivers at least once, so this happens in production without anybody staging it.

    Example: the deletion arrives a second time
      Given 3 people commented on "the cat one"
      And 2 people saved "the cat one" and 3 saved comments under it
      When bob@example.com takes "the cat one" down
      And every part of the portal hears it
      And the same deletion is delivered again
      Then "the cat one" has no comments left
      And nobody has "the cat one" saved
      And the comments of "the cat one" were announced once, not twice

  Rule: Nothing compensates, and the portal says so out loud

    This is the honest cost of a choreography with no orchestrator: there is nobody to notice
    that a hop never ran, nobody to retry it past its own give-up, and nothing to put back. The
    example below is not a bug to be fixed here — it is the shape of the trade, written down.

    Example: the comments part never hears the deletion
      Given 3 people commented on "the cat one"
      And 2 people saved "the cat one" and 3 saved comments under it
      And the comments part hears nothing
      When bob@example.com takes "the cat one" down
      And every part of the portal hears it
      Then nobody has "the cat one" saved
      But the 3 comments of "the cat one" are still there
      And the 3 saved comments of "the cat one" are still there

  Rule: A deletion that names no meme reaches nobody

    Example: an announcement with no meme in it
      Given 3 people commented on "the cat one"
      And 2 people saved "the cat one" and 3 saved comments under it
      When something announces a deletion that names no meme
      And every part of the portal hears it
      Then the 3 comments of "the cat one" are still there
      And the 3 saved comments of "the cat one" are still there
      And the 2 people who saved "the cat one" still have it
