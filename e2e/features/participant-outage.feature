@outage
Feature: A participant's outage — the promise of deletion is kept honest, not kept forever

  What does the person see when they ask to be forgotten while a piece of the portal is down?
  Their account goes into limbo: signing in is refused, no verdict has arrived, and behind the
  curtain the saga keeps re-asking the silent service. The portal does not pretend — it either
  finishes the job or gives the account back and says so in writing.

  This scenario stages the CAPITULATION path, because it is the one that can be arranged
  deterministically: while the favourites service stays stopped, nothing can confirm its purge,
  so the saga's give-up is guaranteed by arithmetic alone — purge timeout 120s from the saga's
  start, sweeper every 15s, 3 re-commands (each delivered instantly, the broker is up) at
  ~127s/~142s/~157s, capitulation on the next sweep at ~172s; security's own 5-minute safety
  net never enters the race. The HAPPY-AFTER-RETURN variant (restart the service in time, the
  saga completes: meme gone, sign-in 401 for good) is real too, but proving it needs the
  restarted container to boot, rejoin the consumer group and purge BEFORE the ~157s retry wall
  — a race against container and rebalance timing, not a fact of arithmetic — so it is
  described here and staged nowhere.

  The steps sample the sign-in door sparingly on purpose: an account in deletion limbo answers
  like a wrong password, and three wrong passwords from one address trip the brute-force block
  — the scenario must watch the limbo without becoming the attacker it looks like.

  Scenario: When the favourites service stays away too long, the account is handed back
    Given a signed-in user with a meme and a favourite
    And the favourites service is down
    When the user asks for their account to be deleted
    Then the account hangs in deletion limbo: signing in is refused and no verdict mail has arrived
    But their meme has already disappeared from the gallery
    When the portal gives up waiting for the favourites service
    Then the account is handed back: signing in works again
    And a mail apologises that the deletion could not be completed
    And their meme stays gone
