Feature: A deleted meme takes its traces along — including the ones in my favourites

  Saving a meme is saving a POINTER to somebody else's thing. When that thing is taken down, the
  pointer is the last one to hear about it, and a favourites list full of memes that no longer
  exist is a list nobody trusts. So the portal cleans up after itself: a meme's deletion travels
  outwards on its own, and everything that pointed at the meme — or at the conversation that hung
  under it — goes with it.

  Nothing orchestrates this. Each service reacts to the hop before it and announces its own, which
  is why every check below waits: the promise is that the list becomes right, not that it is right
  the instant the delete button is released.

  These scenarios speak the user's language on purpose. The mechanics — MEME_DELETED, the dropped
  thread, COMMENTS_DELETED, the item-axis purge — are specified in each service's own features;
  here only what a person sees in their list counts, and it is proven against the LIVE stack.

  Scenario: A meme I saved disappears from my favourites when its author takes it down
    Given a signed-in user who saved a meme of their own in their favourites
    When the author takes that meme down
    Then the meme is gone from the gallery
    And it eventually disappears from my favourites
    And my favourites end up empty

  Scenario: So does a comment I saved, when the meme it hung under is taken down
    Given a signed-in user who saved someone else's comment in their favourites
    When the author takes that meme down
    Then the comment is gone from the thread
    And the saved comment eventually disappears from my favourites
    And my favourites end up empty
