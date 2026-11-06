Feature: The purge-policy default is an ADMIN's dial

  What happens to a leaver's memes is deployment policy — but policy changes
  faster than deployments, so an ADMIN may re-dial the default at runtime. The
  dial answers one question only: what to do when the closure that took the
  memes away named no rule of its own. A rule stated on the closure outranks
  it, and a closure the account's OWN OWNER asked for outranks both — that one
  always deletes, because the right to be forgotten has no exception for memes
  the community liked. Everyone else is refused at the door.

  Rule: The ADMIN's override wins over the deployment default, and the purge obeys it

    Example:
      Given the ADMIN sets the memes purge policy to "ANONYMIZE_AUTHOR"
      Then the effective memes purge policy is "ANONYMIZE_AUTHOR", set by the ADMIN
      When a leaver with one MEME is purged without a stated rule
      Then the leaver's MEME survives anonymised

  Rule: A plain USER may not touch the dial

    Example:
      When a USER tries to set the memes purge policy
      Then the policy change is refused as not-an-ADMIN

  Rule: Clearing the override restores the deployment default

    Example:
      Given the ADMIN sets the memes purge policy to "ANONYMIZE_AUTHOR"
      When the ADMIN clears the memes purge-policy override
      Then the effective memes purge policy is "DELETE", set by the deployment
