@outage
Feature: A participant's outage — the promise of deletion is kept honest, not kept forever

  What does the person see when they ask to be forgotten while a piece of the portal is down?
  Their account goes into limbo: signing in is refused, no verdict has arrived, and behind the
  curtain the saga keeps re-asking the silent service. The portal does not pretend — it either
  finishes the job or gives the account back, WITH the content in it, and says so in writing.

  That last clause is the whole point of ADR 0007, and this scenario is where it is proved. The
  participants answer the purge command by MARKING the leaver's content, which takes it out of
  the gallery immediately and destroys nothing; only the orchestrator's closure erases. So when
  the sweeper capitulates, the compensation puts the memes back — where this scenario used to
  end with a step named "their meme stays gone", i.e. an apology for a deletion that had, in
  fact, happened to everything except the account.

  This scenario stages the CAPITULATION path, because it is the one that can be arranged
  deterministically: while the favourites service stays stopped, nothing can confirm its purge,
  so the give-up is guaranteed by arithmetic alone. The arithmetic, though, is NOT what this
  paragraph claimed until 2026-08-08, and the difference is minutes. The purge timeout is measured
  from the last command the participant actually RECEIVED, not from the saga's birth (P18: "the
  sweeper stops capitulating while a participant is still working"), so a silent participant costs
  120s per delivered command and the whole case takes 120s x 4 = about EIGHT MINUTES. The
  sweeper's 15s cadence only decides how promptly each deadline is noticed.

  For a few hours on 2026-08-08 that had a consequence worth remembering: security's own safety
  net still stood at five minutes, so it fired FIRST and the account came back three minutes before
  its content. Nobody designed that — it was the sum of two independently configured timeouts, one
  of which had moved. The net now sits at twelve minutes, deliberately behind the portal's worst
  case, so the mail this scenario waits for is the PORTAL's verdict again and the content comes
  back with it.

  The HAPPY-AFTER-RETURN variant (restart the service in time, the saga completes: meme gone,
  sign-in 401 for good) is real too, but proving it needs the restarted container to boot, rejoin
  the consumer group and purge before the next 120s wall — a race against container and rebalance
  timing, not a fact of arithmetic — so it is described here and staged nowhere.

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
    And their meme is back in the gallery
