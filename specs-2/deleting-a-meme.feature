Feature: Deleting one meme — what hangs on it, and who else loses a row

  A meme is never alone. The conversation under it was written by people who are not deleting
  anything, and the pointers at it sit in lists belonging to people who have never heard of its
  author. When the meme goes, all of that has to go — or the portal keeps showing its readers
  things that are not there.

  # Nothing is hidden first here, and nothing comes back: one person takes one meme of their own
  # down and means it. That is the whole difference from closing an account, and it is why the two
  # files are separate. How each part learns the meme is gone is not stated — "comments has heard"
  # means that piece of work happened.
  #
  # WHAT HAPPENS WHEN A PART NEVER HEARS IS A HOLE IN THIS FILE. Nobody waits for these parts and
  # nobody checks: a pointer at a meme that no longer exists is the cost, and the last scenario
  # below leaves it standing rather than pretending something collects it.

  Background:
    Given alice posted a meme
    And 3 people commented under it
    And bob saved it
    And carol saved one of the comments under it

  Rule: Nobody is left pointing at what the meme took with it

    Three steps, three parts, and the order is the point: the thread goes because the meme went,
    and carol's pointer goes because the comment went. Neither part could have worked that out on
    its own — only the part holding the thread knows which comments hung under that meme.

    Scenario: the author takes her meme down
      When alice takes her meme down
      Then alice's meme is gone
      But the thread under it is still there
      And bob still has it saved
      When comments has heard that the meme is gone
      Then the thread under it is gone
      When collections has heard that the meme is gone
      Then bob's reference to it is gone
      When collections has heard which comments went
      Then carol's reference is gone

  Rule: Hearing the same thing twice changes nothing and says nothing new

    Whatever carries these facts will deliver some of them more than once, so this happens without
    anybody staging it.

    Scenario: the thread is told a second time
      When alice takes her meme down
      And comments has heard that the meme is gone
      And comments hears the same thing again
      Then the thread under it is gone
      And the comments of that meme were named once, not twice

  Rule: A meme with nothing under it says nothing about comments

    An announcement that names no content carries no fact, so none is made.

    Scenario: a meme nobody commented under
      Given alice posted a second meme nobody commented under
      When alice takes her second meme down
      And comments has heard that the second meme is gone
      Then nothing was said about the comments of the second meme

  Rule: Taking down a meme that is not there reaches nobody

    Scenario: a meme nobody has
      When alice takes down a meme nobody has
      Then alice is told there is no such meme
      And the thread under her meme is still there
      And bob still has it saved
