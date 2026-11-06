Feature: Deleting a MEME

  A MEME belongs to its uploader, who may delete it; a MODERATOR may delete
  anyone's MEME. Everyone else is refused. And a deletion means it: afterwards
  not a byte remains, in any form the service ever made.

  Background:
    Given a MEME uploaded by its author

  Rule: A stranger cannot delete someone else's MEME

    Example:
      When another USER tries to delete it
      Then the deletion is refused as not-theirs
      And the MEME can still be fetched

  Rule: The author deletes their own MEME

    Example:
      When the author deletes it
      Then the deletion succeeds as the author
      And the MEME is gone

  Rule: A MODERATOR deletes anyone's MEME

    Example:
      When a MODERATOR deletes it
      Then the deletion succeeds as a MODERATOR
      And the MEME is gone

  Rule: A GUEST may look, not delete

    Example:
      When a GUEST tries to delete it
      Then the deletion is refused as sign-in required

  Rule: After a deletion not a byte of the MEME remains

    Example:
      Given a browser has already been served its WebP variant
      When a MODERATOR deletes it
      Then the deletion succeeds as a MODERATOR
      And not a byte of the MEME remains, in any form
