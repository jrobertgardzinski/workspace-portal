Feature: The closure protocol — five states, three reports, and what each state refuses

  Something has to notice that every part has hidden its share and only then let the account go;
  something has to know the account is gone before any part destroys anything, and know it stayed
  before any part brings anything back. This file is that something, as small as it can be: a
  state machine with five states, the reports a part makes in each of them, and a table of what is
  refused.

  ../specs-2 is written before any design and says what has to be true of the CONTENT. This file
  is what sits between the two: the protocol every design has to keep, whatever the design is. A
  monolith keeps it with one transaction — the states are moments of that transaction and a refused
  report is a rollback; separate services keep it with a saga — the states are rows somewhere and
  a refused report is a message nobody acts on. The same scenarios have to pass against both, and
  the runner drives ../specs-2 through this machine, so a state named there ("Given the closure
  has reached DESTROYING") is a state defined here.

  # The states, and the two roads through them:
  #   HIDING --(every part hidden, the account goes)------> DESTROYING --(every part destroyed)--> DONE
  #   HIDING --(the account refuses, or the closure is given up on)--> RESTORING --(every part that hid restored)--> WITHDRAWN
  #
  # The reports: a part reports its share HIDDEN, DESTROYED or RESTORED; the closure is GIVEN UP ON.
  # A report is accepted, is nothing new (the same report a second time), or is refused — and a
  # refused report changes nothing, which is the whole point of having states.
  #
  # What stays outside this file: what carries a report from a part to the machine, how long it
  # waits for a part that never reports, and who decides to give up. The machine knows the three
  # parts by name and nothing else about them.

  Background:
    Given a closure of alice's account was requested

  Rule: HIDING — the parts hide their shares, and the third report is what lets the account go

    Scenario: a closure opens in HIDING and asks nothing of the account yet
      Then the closure is HIDING
      And the account has not been asked to go

    Scenario: two parts reported is still HIDING
      When memes reports its share hidden
      And comments reports its share hidden
      Then the closure is HIDING
      And the account has not been asked to go

    Scenario: the third report asks the account to go, and the closure moves on
      Given memes has reported its share hidden
      And comments has reported its share hidden
      When collections reports its share hidden
      Then the account is gone
      And the closure is DESTROYING

    Scenario: the same part reporting twice is nothing new
      Given memes has reported its share hidden
      When memes reports its share hidden again
      Then the report is nothing new
      And the closure is HIDING

  Rule: When the account cannot go, the closure turns back — RESTORING

    The two ways in: the row refuses to be deleted, or whoever is waiting for the parts stops
    waiting. Either way the account is unlocked, and what was hidden has to come back.

    Scenario: the account refuses to go
      Given the account will refuse to go
      And memes has reported its share hidden
      And comments has reported its share hidden
      When collections reports its share hidden
      Then the account was asked to go
      But the account is still there
      And the account was unlocked
      And the closure is RESTORING

    Scenario: the closure is given up on while the parts are still hiding
      Given memes has reported its share hidden
      When the closure is given up on
      Then the report is accepted
      And the account has not been asked to go
      And the account was unlocked
      And the closure is RESTORING

    Scenario: given up on before any part hid, there is nothing to wait for
      When the closure is given up on
      Then the account was unlocked
      And the closure is WITHDRAWN

    Scenario: giving up a closure that has already turned back is nothing new
      Given the closure has reached RESTORING
      When the closure is given up on
      Then the report is nothing new
      And the closure is still RESTORING

  Rule: DESTROYING — every part destroys what it hid, and the third report ends the closure

    Scenario: the closure is DONE when the last part has destroyed its share
      Given the closure has reached DESTROYING
      When memes reports its share destroyed
      And comments reports its share destroyed
      Then the closure is DESTROYING
      When collections reports its share destroyed
      Then the closure is DONE

    Scenario: the same part destroying twice is nothing new
      Given the closure has reached DESTROYING
      And memes has reported its share destroyed
      When memes reports its share destroyed again
      Then the report is nothing new
      And the closure is DESTROYING

  Rule: RESTORING — only the parts that hid have anything to bring back, and only they are waited for

    Scenario: every part that hid brings its share back, and the closure is WITHDRAWN
      Given the closure has reached RESTORING
      When memes reports its share restored
      And comments reports its share restored
      Then the closure is RESTORING
      When collections reports its share restored
      Then the closure is WITHDRAWN

    Scenario: a part that never hid is not waited for
      Given memes has reported its share hidden
      And the closure is given up on
      When memes reports its share restored
      Then the closure is WITHDRAWN

    Scenario: a part that never hid has nothing to bring back
      Given memes has reported its share hidden
      And the closure is given up on
      When collections reports its share restored
      Then the report is refused
      And the closure is still RESTORING

  Rule: A report out of its state is refused and changes nothing

    This table is the two sentences of ../specs-2, said the other way round: nothing is destroyed
    until the account is gone, because DESTROYED is accepted in DESTROYING alone; once the account
    is gone nothing comes back, because RESTORED is refused there; and a closure that has ended
    accepts nothing at all.

    Scenario Outline: <report> while the closure is <state>
      Given the closure has reached <state>
      When <report>
      Then the report is refused
      And the closure is still <state>

      Examples: nothing is destroyed until the account is gone
        | state      | report                                |
        | HIDING     | memes reports its share destroyed     |
        | RESTORING  | memes reports its share destroyed     |
        | WITHDRAWN  | memes reports its share destroyed     |

      Examples: once the account is gone, nothing comes back and nobody gives up
        | state      | report                                |
        | DESTROYING | memes reports its share restored      |
        | DESTROYING | the closure is given up on            |
        | DONE       | memes reports its share restored      |
        | DONE       | the closure is given up on            |

      Examples: nothing is brought back that was not hidden first
        | state      | report                                |
        | HIDING     | memes reports its share restored      |

      Examples: nothing is hidden once the account's fate is decided
        | state      | report                                |
        | DESTROYING | memes reports its share hidden        |
        | RESTORING  | memes reports its share hidden        |
        | DONE       | memes reports its share hidden        |
        | WITHDRAWN  | memes reports its share hidden        |

      Examples: a closure that has ended accepts nothing
        | state      | report                                |
        | DONE       | memes reports its share destroyed     |
        | WITHDRAWN  | memes reports its share restored      |
        | WITHDRAWN  | the closure is given up on            |
