Feature: Getting into the gallery — every door the panel offers
  The gallery's sign-in panel is the portal's front door, and it has more than one lock: a fresh
  account made right here and confirmed by the mailed link, a plain password, a password plus a
  mailed sign-in code when the account carries a second factor — and, when the code cannot reach
  you, a recovery code in its place. These scenarios drive the real panel in a real browser
  against real security; only the mailbox is a test-environment backdoor, because a browser
  cannot read e-mail.

  Scenario: A visitor makes an account here and the mailed link lets them in
    Given the visitor opens the gallery
    When they create an account from the panel
    Then the panel says to check the mail
    When they follow the link from that mail
    Then the panel says the address is verified
    And signing in with the new account works

  Scenario: The wrong password does not open the door
    Given a verified account exists
    And the visitor opens the gallery
    When they sign in with the wrong password
    Then the panel refuses them
    And the panel does not show them as signed in

  Scenario: An unverified account is sent back to its mailbox, not let in
    Given an account exists that never followed its verification link
    And the visitor opens the gallery
    When they sign in with that account
    Then the panel says the address is not verified yet
    And the panel does not show them as signed in

  Scenario: A second factor adds the code step to the same password
    Given a verified account exists
    And the account carries a mailed sign-in code factor
    And the visitor opens the gallery
    When they sign in with that account
    Then the panel asks for the sign-in code
    When they type the code from their mail
    Then the panel shows they are signed in

  Scenario: A recovery code stands in for the mailed one
    Given a verified account exists
    And the account carries a mailed sign-in code factor
    And the account has recovery codes
    And the visitor opens the gallery
    When they sign in with that account
    Then the panel asks for the sign-in code
    When they type a recovery code instead
    Then the panel shows they are signed in
