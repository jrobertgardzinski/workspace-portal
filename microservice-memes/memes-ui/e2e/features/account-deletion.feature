Feature: Leaving — deleting the account, content and all
  The danger zone in the panel is the RODO exit, and it is deliberately not a single click: the
  visitor proves it is really them (step-up — a stolen session must not be able to end an
  account). What they posted is NOT a choice they are offered: a closure somebody requests for
  themselves destroys everything they wrote, and only an ADMIN closing SOMEBODY ELSE's account may
  attach conditions. What follows is a SAGA across the whole portal: security announces the
  deletion, offboarding orders every participant to purge, memes, comments and collections do it
  and confirm, and only then is the account gone for good.

  These scenarios drive that entire road in a real browser against real services on a real
  broker — no member of the chain stubbed out, because an end-to-end missing a member proves
  nothing about the member it skipped.

  Scenario: Leaving takes the account AND the memes with it
    Given a verified account exists
    And the visitor opens the gallery
    And signs in with that account
    And they upload an image
    Then their meme is on the wall
    When they open the danger zone
    And confirm the deletion with their password
    Then the panel says the deletion started
    And signing in with that account is refused
    And their meme is gone from the wall

  Scenario: What they wrote under someone else's meme goes with them
    Given a meme has been uploaded by someone
    And a verified account exists
    And the visitor opens the gallery
    And signs in with that account
    And opens the meme
    When they post the comment "I was here before I left"
    And close the meme
    And they open the danger zone
    And confirm the deletion with their password
    Then the panel says the deletion started
    And signing in with that account is refused
    And the comment "I was here before I left" is gone from the thread

  Scenario: The wrong password does not end an account
    Given a verified account exists
    And the visitor opens the gallery
    And signs in with that account
    When they open the danger zone
    And confirm the deletion with the wrong password
    Then the dialog complains about the password
    And signing in with that account still works

  Scenario: Second thoughts leave everything alone
    Given a verified account exists
    And the visitor opens the gallery
    And signs in with that account
    When they open the danger zone
    And keep their account instead
    Then the panel shows they are signed in
    And signing in with that account still works

  Scenario: A second factor is asked for on the way out too
    Given a verified account exists
    And the account carries a mailed sign-in code factor
    And the visitor opens the gallery
    And signs in with that account through the code step
    When they open the danger zone
    And confirm the deletion with their password
    Then the dialog asks for the sign-in code
    When they confirm the deletion with the code from their mail
    Then the panel says the deletion started
    And signing in with that account is refused
