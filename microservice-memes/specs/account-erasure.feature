# The purge arrives over the broker and not over HTTP; the wire — the envelope, the saga id,
# the confirmation that answers the orchestrator — is pinned by the pact tests and deliberately
# absent here. What this file describes is the PROMISE this service makes to the three others
# it shares a leaver with (ADR 0007).
Feature: What a leaver's pictures are owed

  Anything this service holds of a person who is leaving has to go. Until somebody says
  the decision is final, it has to be possible to give it all back — so the MEMES first
  leave the gallery, the whole of what the leaver asked to see, and only later leave the
  disk.

  Being out of sight is not a courtesy here. Every other service holding that person's
  things is deciding at the same time, and any one of them may fail. This is not a
  theoretical worry: a service that shredded the pictures the moment it was asked left
  nothing to give back, and the leaver got their account restored without their memes
  and an e-mail apologising for a deletion that had not, in fact, been cancelled.

  The image leaving object storage is the point past which nothing can be taken back,
  so nothing may reach it before the decision is final. Being asked twice is normal and
  changes nothing — the second request finds the work already done.

  Background:
    Given a leaver with one MEME in the gallery

  Rule: Nothing is destroyed until the decision is final

    Example:
      When the ORCHESTRATOR commands the PURGE of their content
      Then the MEME is gone from the gallery
      But the MEME is still stored
      When the comments service never confirms and the ORCHESTRATOR compensates
      Then the MEME is back in the gallery
      And the MEME is still stored

  Rule: Once it is final, the pictures are gone for good

    Example:
      When the ORCHESTRATOR commands the PURGE of their content
      And every participant confirms and the ORCHESTRATOR closes the SAGA
      Then the MEME is gone from the gallery
      And the image itself is gone for good
      And a late compensation brings nothing back

  Rule: While the SAGA holds a MEME, nobody takes it down

    # the reservation is only worth making if it HOLDS: everything the orchestrator may still
    # undo has to be there to undo. A MEME set aside is out of the gallery, and a takedown —
    # its author's or a MODERATOR's — reaches what the gallery lists, so it finds nothing and
    # changes nothing. Without this, an ordinary delete arriving between the two halves of the
    # SAGA would take the row out from under it: the count already confirmed to the ORCHESTRATOR
    # would be a lie, and the compensation would give back less than it took.

    Example:
      When the ORCHESTRATOR commands the PURGE of their content
      And the AUTHOR tries to delete their own MEME
      Then the takedown finds nothing
      When a MODERATOR tries to delete the MEME
      Then the takedown finds nothing
      And the MEME is still stored
      When the comments service never confirms and the ORCHESTRATOR compensates
      Then the MEME is back in the gallery
      And the MEME is still stored

  Rule: Being asked twice is the same as being asked once

    Example:
      When the ORCHESTRATOR commands the PURGE of their content
      And the ORCHESTRATOR commands the PURGE of their content
      Then the MEME is gone from the gallery
      And the MEME is still stored
      When the comments service never confirms and the ORCHESTRATOR compensates
      Then the MEME is back in the gallery
