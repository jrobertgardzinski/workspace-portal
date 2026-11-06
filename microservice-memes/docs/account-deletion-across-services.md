# Deleting an account, end to end — the one page this repo was missing

The hardest thing to understand about this service is not in this service. Every module here is
small and layered; what takes days to reconstruct is the answer to *"what actually happens when
a user deletes their account?"*, because the answer lives in four repositories and nowhere as a
whole. This page is that whole. It is written from the code and verified by the browser suite in
`memes-ui/e2e/features/account-deletion.feature`, which drives exactly this road against the
real stack.

## The road, in order

```
gallery (memes-ui)                    security                     offboarding                 memes / comments / user-collections
      │                                  │                              │                                    │
 1. POST /account/step-up ──────────────►│                              │                                    │
      │  {action: delete-account, pwd}   │ 200 ELEVATED                 │                                    │
      │                                  │ …or 202 FACTOR_REQUIRED      │                                    │
 2. POST /account/step-up/factor ───────►│  (only when a factor is enrolled)                                  │
      │                                  │                              │                                    │
 3. POST /account/delete {purge?} ──────►│ 202                          │                                    │
      │                                  │ locks the account NOW        │                                    │
      │                                  │ (users.pending_deletion)     │                                    │
      │                                  │                              │                                    │
      │                     outbox row ──┤                              │                                    │
      │            security-events:      │                              │                                    │
      │            ACCOUNT_DELETION_REQUESTED ─────────────────────────►│                                    │
      │                                  │                              │ content-commands:                  │
      │                                  │                              │ PURGE_USER_CONTENT ───────────────►│
      │                                  │                              │        MARK: status=PENDING_ERASURE│
      │                                  │                              │        (invisible, nothing deleted)│
      │                                  │                              │◄── memes-events ───────────────────┤
      │                                  │                              │◄── comments-events ────────────────┤
      │                                  │                              │◄── usercollections-events ─────────┤
      │                                  │                              │ (all participants confirmed)       │
      │                                  │                              │ content-commands:                  │
      │                                  │                              │ ERASE_USER_CONTENT ───────────────►│
      │                                  │                              │        apply the rule, delete rows │
      │                                  │                              │  ╔═════════════════════════════════╗
      │                                  │                              │  ║ >>> THE PIVOT <<<               ║
      │                                  │                              │  ║ the image leaves MinIO/S3.      ║
      │                                  │                              │  ║ Past here: retry only,          ║
      │                                  │                              │  ║ never compensation.             ║
      │                                  │                              │  ╚═════════════════════════════════╝
      │                                  │  offboarding-events:         │                                    │
      │                                  │◄─ PORTAL_CONTENT_PURGED ─────┤                                    │
      │                                  │ deletes the user for good    │                                    │
      │                                  │ mails ACCOUNT_DELETED        │                                    │
```

And the road NOT taken — the same picture when a participant stays silent:

```
      │                                  │                              │ (the sweeper gives up)             │
      │                                  │                              │ content-commands:                  │
      │                                  │                              │ RESTORE_USER_CONTENT ─────────────►│
      │                                  │                              │        status back to ACTIVE:      │
      │                                  │                              │        the content is public again │
      │                                  │  offboarding-events:         │                                    │
      │                                  │◄─ PORTAL_PURGE_FAILED ───────┤                                    │
      │                                  │ unlocks the account          │                                    │
      │                                  │ mails the apology            │                                    │
```

1. **Step-up, not a click.** The danger zone proves it is really you before anything happens: the
   password, and — when the account carries a second factor — a code on top of it. A stolen
   session cannot end an account. (`DeleteAccountDialog.tsx`; security's `StepUp` +
   `StepUpGuard`.)
2. **The lock is immediate, the deletion is not.** `POST /account/delete` answers 202 and marks
   the account `pending_deletion`. From this moment the user cannot sign in — which is *not* the
   same as being deleted, and is the single most common misreading of this flow.
3. **The wish travels as a fact.** Security writes `ACCOUNT_DELETION_REQUESTED` to its
   transactional outbox in the same transaction as the lock, and the poller publishes it to
   `security-events`. If the leaver picked a policy in the wizard, it rides along as `policy`.
4. **offboarding is the process manager.** It turns the fact into one `PURGE_USER_CONTENT`
   command on `content-commands` and then waits for every participant it was configured with
   (`OFFBOARDING_PARTICIPANTS`, `name=confirmation-topic` pairs). Participants are configuration:
   a new content service joins the saga there, without a line changing in security.
5. **Each participant MARKS, and marking is not deleting.** This service's side is
   `PurgeCommandsListener` → `MarkUserContentForErasure`: the leaver's memes get
   `status = PENDING_ERASURE` and leave the gallery, the tag search, the ranking and every image
   URL at once — because all of those read through the `active_memes` view — while row, blob,
   votes and authorship stay exactly where they were. The confirmation therefore means
   *reserved*, not *destroyed*, and a reservation can be given back. (ADR 0007.) It also says HOW
   MANY — `reserved` in the payload — because a mark that matched nothing used to be reported in
   exactly the same words as one that emptied a whole gallery, and the commonest way to match
   nothing is a member whose address moved (see the README).
6. **The closure is what deletes.** Once every participant has confirmed, offboarding sends
   `ERASE_USER_CONTENT` on the same topic, and only then does `PurgeUserContent` apply the rule
   per axis (`DELETE` / `ANONYMIZE_AUTHOR` / `KEEP_POPULAR_ANONYMIZED:n`, the table in the
   README). The rule is read HERE and not at the mark, because it scores votes and the leaver's
   own votes are only retracted by the erasure itself. **The pivot is inside this step**: the
   image leaving object storage is the one act no message can undo, which is why the saga only
   reaches it when nothing can fail any more, and why the obligation to finish it is durable
   (`pending_blob_deletes`, V9) rather than a promise in one JVM's memory.
7. **Only then is the account gone.** offboarding announces `PORTAL_CONTENT_PURGED` on
   `offboarding-events`; security's `OffboardingOutcomeListener` finishes the deletion for real
   and mails the goodbye. The account exists until that message arrives.

## When it goes wrong

- **A participant never confirms.** offboarding's sweeper gives up after its own timeout, sends
  `RESTORE_USER_CONTENT` to **every** participant — including ones whose confirmation was merely
  lost, because restoring what was never marked is a no-op — and only then announces
  `PORTAL_PURGE_FAILED`; security unlocks the account and mails the apology. The leaver gets
  their account back **with their memes and comments in it**: that is what the mark bought, and
  before it existed the same path handed back an empty account with an apology for a deletion
  that had, in fact, happened.
- **A closure is lost.** The marks stay on, the content stays hidden and nothing deletes it on a
  timer — deliberately. The closure and the verdict are published and marked announced together,
  so the next sweep re-publishes both; if it never does, `StuckErasureWatch` gauges the backlog
  (`memes_erasure_backlog`) and says in the log that the content is hidden but not erased.
- **All three participants behave this way**, user-collections included. Its own difference is
  that the refs it saves are opaque, so its closure applies no rule — it deletes exactly the rows
  the mark reserved, in one statement.
- **Security's own safety net** (`account-deletion.purge-timeout`, **12 min**) sits deliberately
  *after* the portal's WORST CASE — `purgeTimeout x (retries + 1)` = 8 minutes — so the portal's
  failure announcement wins the race and the net is left for what it is for: a coordinator that
  died. It stood at 5 min until 2026-08-08 and had quietly become the first of the two to fire.
- **Identity-only deployments** (no portal at all) set `account-deletion.await-portal-purge=false`
  and delete immediately. Handy to know, dangerous to test with: see the trap below.

## Traps that cost an evening if nobody tells you

- **Security's `test` environment has no outbox publisher at all** —
  `@Requires(notEnv = "test")` on `OutboxPublisher`. Nothing ever reaches a broker there, so a
  deleted account merely locks and any test asserting "cannot sign in" passes while proving
  nothing about the saga. This is why the browser suite runs against the real stack.
- **`mvn package` without `clean` can ship a stale UI.** `jar:jar` skips when it thinks the
  archive is current and Spring Boot's `repackage` then wraps the old one — the container serves
  yesterday's gallery bundle while your source shows today's fix. `clean package` settles it.
- **A shared gallery is full of other people's memes.** "The first tile on the wall" is not
  yours; ask the service whether a meme is yours (`GET /memes/{id}/meta` WITH your bearer token
  and read `own` — the endpoint is public, so it masks the uploader and never answers "who").
- **Codes are one-shot on both sides.** A scenario (or a person) that signs in twice must read
  two different mails; re-typing the first code fails with a confusing "wrong code".

## Where the pieces live

| what | where |
|------|-------|
| the wizard, the step-up dialog | `memes-ui/src/DeleteAccountDialog.tsx` |
| the lock, the outbox fact, the final deletion, the mails | `../../shared/microservice-security` (`AccountDeletionOrchestrator`, `OffboardingOutcomeListener`) |
| the process manager, participants, the sweeper | `../microservice-offboarding` |
| this service's mark, its compensation and the erasure | `memes-infrastructure/.../PurgeCommandsListener`, `memes-system/.../{MarkUserContentForErasure,RestoreUserContent,PurgeUserContent}` |
| why it is a status and not a queue table, and where the pivot is | `../../shared/docs/adr/0007-soft-delete-by-status-for-a-compensatable-offboarding-saga.md` |
| the two-phase road, executable | `specs/account-erasure.feature` |
| the same road, executable | `memes-ui/e2e/features/account-deletion.feature` (real stack, no stubs) |
