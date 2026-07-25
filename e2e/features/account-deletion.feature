Feature: The right to be forgotten — leaving the portal takes the user's traces along

  A person who asks for their account to be deleted is owed more than a dead login: the meme
  they posted, the comment they signed and the list of things they saved all have to be dealt
  with, each according to the portal's data policy. By default their memes disappear and their
  comments stay readable for the thread's sake — but signed by nobody. The leaver may also
  choose a stricter fate for their words.

  These scenarios speak the user's language on purpose. The choreography underneath — the
  deletion fact, the purge commands, the participants' confirmations — is already specified
  in microservice-offboarding's own features; here only the promise made to the person counts,
  and it is proven against the LIVE stack: real services, real broker, real mailbox.

  Scenario: Deleting the account removes the person's traces under the default policy
    Given a signed-in user with a meme, a comment and a favourite
    When the user asks for their account to be deleted
    Then their meme disappears from the gallery
    And their comment stays readable but is signed "deleted account"
    And their list of favourites is empty
    And they can no longer sign in
    And a farewell mail tells them the account is deleted

  Scenario: The leaver may choose that their words go too
    Given a signed-in user who commented on someone else's meme
    When the user asks for their account to be deleted, choosing that their comments be erased
    Then their comment disappears from the thread entirely
    And they can no longer sign in
