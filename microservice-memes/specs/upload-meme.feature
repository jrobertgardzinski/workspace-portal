Feature: Uploading a MEME

  A GUEST browses freely — the gallery, every MEME, every thumbnail; uploading
  takes a USER. A MEME is stored optimised for the browser, and the door checks
  what it is handed — a file that is not an honest image is turned away
  politely, never crashed on.

  Rule: An uploaded MEME is public to fetch, thumbnail and all

    Example:
      Given a USER
      When the USER uploads a BMP image
      Then the MEME is stored
      And fetching it without signing in returns a PNG

    Example:
      Given a USER
      When the USER uploads a BMP image
      Then fetching its thumbnail returns a PNG

  Rule: The gallery lists the MEME publicly

    Example:
      Given a USER
      When the USER uploads a BMP image
      Then the gallery lists it without signing in

  Rule: A GUEST may browse, not upload

    Example:
      When a GUEST tries to upload a BMP image
      Then the request is refused as sign-in required

  Rule: A file that is not an honest image is turned away, not crashed on

    Example:
      Given a USER
      When the USER uploads a text file pretending to be an image
      Then the upload is turned away with a polite explanation

    Example:
      Given a USER
      When the USER uploads a tiny file declaring absurd image dimensions
      Then the upload is turned away with a polite explanation
      And the gallery still welcomes the next honest upload
