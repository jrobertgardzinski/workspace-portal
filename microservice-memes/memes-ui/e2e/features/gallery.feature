Feature: The meme gallery in a real browser
  The gallery is public to browse; uploading, voting and commenting need a signed-in identity
  from microservice-security. These scenarios drive the React UI with Playwright against the LIVE
  compose stack — real services on real Postgres, real Kafka, real mailbox — which run-e2e.sh
  brings up. The header used to say "(in-memory stores)", a leftover from the old four-jar
  harness; the sibling features already describe the live stack correctly, and overstating or
  understating what a suite proves is the one thing living documentation must not do.

  Scenario: An anonymous visitor browses the gallery
    Given a meme has been uploaded by someone
    When the visitor opens the gallery
    Then the meme's tile is on the wall
    And opening it shows "sign in to vote or comment"

  Scenario: Signing in through the panel
    Given a verified account exists
    When the visitor opens the gallery
    And signs in with that account
    Then the panel shows they are signed in

  Scenario: An upload appears on the wall
    Given a verified account exists
    And the visitor opens the gallery
    And signs in with that account
    When they upload an image
    Then the meme's tile is on the wall

  Scenario: A vote is a toggle
    Given a meme has been uploaded by someone
    And a verified account exists
    And the visitor opens the gallery
    And signs in with that account
    And opens the meme
    When they vote it up
    Then the score shows 1
    When they vote it up
    Then the score shows 0

  Scenario: A comment lands in the thread
    Given a meme has been uploaded by someone
    And a verified account exists
    And the visitor opens the gallery
    And signs in with that account
    And opens the meme
    When they post the comment "Lorem ipsum from the browser"
    Then the thread shows "Lorem ipsum from the browser"
