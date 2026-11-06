# microservice-memes

> 👋 **Welcome, and thanks for looking!** This is a meme gallery that looks simple from the
> browser and is deliberately grown-up underneath. Start with [`specs/`](./specs) — one Gherkin
> file per use case, and every one **executable**: Cucumber drives them over real HTTP in every
> build, so everything they describe is verified, not aspirational:
> [upload](./specs/upload-meme.feature) ·
> [vote](./specs/vote-meme.feature) ·
> [tag](./specs/tag-meme.feature) ·
> [delete](./specs/delete-meme.feature) ·
> [flag NSFW](./specs/flag-meme.feature) ·
> [admin purge policy](./specs/admin-purge-policy.feature) ·
> [account erasure](./specs/account-erasure.feature)

## Highlights — what's worth a closer look

- **The framework is a detail**: Spring Boot lives in **one module of nine**; domain, use cases,
  taking content down, image processing and tags compile and test without it.
- **Upload pipeline doubles as a security boundary**: every image is re-encoded (a crafted JPEG
  with GPS EXIF goes in, a clean PNG comes out — there's a test), bounded by size limits and a
  per-uploader rate limit (429 + `Retry-After`).
- **Content-hash dedup that survives a race**: two concurrent uploads of the same bytes yield
  exactly one meme — an atomic *claim* (unique constraint in Postgres), saving only after the
  claim is won; pinned by a two-thread test.
- **Image bytes behind a port**: one `ObjectStore` port, three adapters (DB blob / filesystem /
  S3-MinIO), switched by env — the hexagonal promise kept for real.
- **Graceful degradation**: WebP is encoded by a sidecar service and cached once per meme; if the
  encoder is down you get PNG — quality degrades, availability doesn't.
- **Identity done right**: reads are public, writes need a bearer token confirmed against
  `microservice-security`; the author is the **confirmed identity**, never a request field; a
  privileged role without completed MFA is demoted at the gate (fail-closed MFA floor).
- **A real distributed flow**: this service is a participant in the account-deletion saga —
  the leaver's content follows a configurable rule per axis (`DELETE` / `ANONYMIZE_AUTHOR` /
  `KEEP_POPULAR_ANONYMIZED:n`), the leaver's own choice wins, and their votes are always
  retracted; confirmations flow back over Kafka.
- **Contracts, not hope**: Pact pins both what this service reads from the saga command
  (message pact) and from `GET /me` (HTTP pact); event envelopes carry a version and evolve
  additively.
- **Proven at every level**: unit → Cucumber black-box over HTTP → a Playwright suite driving
  the gallery in a real Chromium → whole-stack smoke; metrics, correlation-id logs and
  distributed traces out of the box (one upload = one trace across memes and security).

Part of a [portfolio of microservices](https://github.com/jrobertgardzinski) exploring the same
architecture across six frameworks; this one plays the Spring Boot flavour.

---

A meme service: upload images, serve them optimised for the browser (with on-demand thumbnails and
content-hash deduplication) and vote ("hot" ranking); comment threads moved to
`microservice-comments`, voting semantics come from the shared `voting` library. More monolithic than the split
libraries of `microservice-security`, but still layered into small modules. **Framework: Spring
Boot** — the third flavour in the portfolio after Micronaut (`microservice-security`, hexagonal)
and Quarkus (`microservice-email`, BCE).

## Modules

- **memes-domain** — the entities (`Meme`, `MemeMetadata`, `MemeStatus`, `RankedMeme`,
  `ScoredMeme`, `Observation`) **and every port they are read and written through**
  (`MemeRepository`, `MemeErasure`, `MemeContentIndex`, `TagRepository`, `VoteRepository`,
  `MemeEvents`, `PurgePolicyOverride`) — four of them with their fake and its contract test
  beside them. Pure Java.
- **memes-config** — typed, validated configuration values (`ImageLimits`, `ThumbnailSize`,
  `TagLimits`, `RateLimit`, `ErasureTolerance` — how long a mark may stand before it reads as a
  closure that never came). Pure Java. What an account deletion does to the leaver's content is
  `PurgeRule`, and it is shared vocabulary: it lives in `portal-libs`.
- **memes-image** — `WebImageOptimizer`: re-encodes any ImageIO-readable image (BMP, JPEG, …) to a
  browser-friendly PNG (which also drops EXIF), bounded by the configured limits. Pure JDK
  (`java.desktop`).
- **memes-tags** — the `Tag` value object (normalisation and validation). Pure Java.
- **memes-application** — the use cases that put content UP and read it back (`PublishMeme`,
  `ServeMeme`, `MakeThumbnail`, `ViewMeme`, `ListMemes`, `SearchMemesByTag`, `TagMeme`,
  `FlagMeme`, `CastVote`, `ShowMemeVote`, `ShowMemeScores`, `RankMemes`) + three technical ports
  (`ObjectStore`, `ImageEncoder`, `ContentFlags`). No framework.
- **memes-system** — the use cases that take content DOWN, below the ones that put it up and
  reachable without them: `DeleteMeme`, and the account-closure trio
  `MarkUserContentForErasure` / `PurgeUserContent` / `RestoreUserContent`, plus
  `WatchErasureBacklog`. No framework — so a spec can drive the deletion semantics on domain +
  system alone, with no transport and no decision about monolith versus services.
- **memes_account-closure** — this service's side of ONE cross-service process: what happens to a
  person's memes when their account closes. The three commands (mark, erase, restore), which one
  is reversible, which rule applies and what gets confirmed — with no Kafka, no database and no
  Spring anywhere in it, so the flow can be read and tested before anyone decides whether the
  portal is six services or one. The underscore says it is not a layer: `memes-<x>` is a layer of
  this service, `memes_<x>` is its part in the process named after the shared library `<x>`
  (here `account-closure`), and `comments_account-closure` would be the other end of the same
  conversation.
- **memes-ui** — the gallery UI: React + TypeScript + Material UI, built by Vite through
  frontend-maven-plugin (own pinned Node) and packed as `META-INF/resources`, so the service jar
  serves it at `/`. UI development: `cd memes-ui && npm run dev` (proxies `/memes` to :8083).
- **memes-infrastructure** — the Spring Boot app: web boundaries (`MemeController`,
  `VoteController`, `TagController`, `AdminController`), the sign-in gate (`RequireSignInFilter`
  + `HttpSecurityAuthenticationGate`, which confirms bearer tokens against
  `microservice-security`'s `GET /me`), the JDBC adapters and the three blob stores. The Cucumber
  runner drives the top-level `specs/` features here; results feed Allure.

## Security integration

Browsing is public; **contributing requires signing in**. Every `POST` under `/memes` must carry
`Authorization: Bearer <access token issued by microservice-security>`; the gate confirms it via
`GET /me` (config: `security.url` / `SECURITY_URL`) and the confirmed identity becomes the
meme's author — the request body cannot impersonate anyone. Anonymous writes get
`401 {"status": "SIGN_IN_REQUIRED"}` — a statement about the caller's session, which the gallery
acts on by signing them out. A write whose token could not be confirmed **because security did not
answer** gets `503 {"status": "SECURITY_UNAVAILABLE"}` instead: nobody judged that token, and an
outage of another service is not a reason to end a live session. Reads carrying a token are served
anonymously during such an outage rather than refused.

## Account deletion (the saga's memes side)

> **The whole road, across all four services, is one page:
> [docs/account-deletion-across-services.md](docs/account-deletion-across-services.md)** — the
> step-up, the immediate lock (which is NOT deletion), the outbox fact, offboarding as process
> manager, every participant's confirmation, and what happens when one never comes. Read it
> before changing anything here; this section only describes the piece that runs in this repo.


`PURGE_USER_CONTENT` commands arrive over Kafka. What happens to the leaver's content is a
**rule per axis** (their memes / their comments), decided in two places: the deployment default
(`PURGE_MEMES_POLICY` / `PURGE_COMMENTS_POLICY`) and — taking precedence — **the leaver's own
choice from the deletion wizard**, carried inside the saga command:

| rule | effect |
|------|--------|
| `DELETE` | the content disappears (a meme takes its whole comment thread and votes along) |
| `ANONYMIZE_AUTHOR` | the content stays, authored by "deleted account" |
| `KEEP_POPULAR_ANONYMIZED:<n>` | items with score ≥ n survive anonymised (the community earned them); the rest is deleted |

Defaults: memes `DELETE`, comments `ANONYMIZE_AUTHOR`. Votes the leaver cast are always
retracted — identity-keyed data has no policy escape hatch. Unparseable rules in a command fall
back to the defaults (logged), never wedging the saga.

**A member's address can move, and nothing here moves with it.** Every row is keyed by the
member's id — `memes.author_id`, and `meme_votes.voter`, which holds the voter's id in its wire
form — so a rename touches no content row and there is no Kafka loop to carry one (workspace
ADR 0008). What the gallery and `/meta` show as an author is not stored here at all: it is fetched
at read time from security through `AuthorDirectory` (`GET /users?ids=`, masked addresses, cached
for 60 s), and when the directory is unreachable the page still renders while the log says the
names are unavailable. "deleted account" is likewise not a name in a row — it is `author_id NULL`,
written by an anonymising closure, which also means kept content cannot be grouped back together by
the id of the account that is gone. One address survives, and it is not on a meme:
`settings.updated_by`, an audit snapshot of who changed a setting. The gallery's own tables hold
no address at all — a build-time guard (`RetiredAddressKeyTest`) fails the suite if the column,
or a query keyed by one, comes back. The
confirmation sent back on `memes-events` still carries `reserved` — how many memes the mark
actually took out of the gallery — and a zero still raises
`memes_saga_purge_reserved_nothing_total` instead of reading as a successful erasure: a closure that
reserved nothing is either a member who posted nothing or a mark that missed, and the two must not
read alike.

## Contract

```
GET  /                          the gallery UI (React, served from the jar)

# public reads
GET  /memes[?tag=...][&page=0&size=50]
                                -> 200 [ { "id", "nsfw" }, ... ]  (newest first)
                                   one PAGE, never the whole wall: size is capped at 100,
                                   an out-of-range page is an empty list
GET  /memes/{id}                -> 200 image/png — or image/webp when Accept allows it | 404
GET  /memes/{id}/thumbnail      -> 200 image/png (small preview) | 404
GET  /memes/{id}/meta           -> 200 { "id", "author", "own", "nsfw" } | 404
                                   author is MASKED (a***@domain) — a public endpoint hands out
                                   no addresses; "own" answers "is this mine?" from the bearer
                                   token if one is sent (false for anonymous callers)
GET  /memes/{id}/tags           -> 200 [ "tag", ... ]
GET  /memes/{id}/votes          -> 200 { "score": n, "myVote": ... } | 404
GET  /memes/hot                 -> 200 [ { "memeId", "score" }, ... ]  (hottest first, top 100)
                                   a RANKING, i.e. an ORDER — not a lookup table of scores:
                                   everything past the cap is missing from it, so a client
                                   reading it as a dictionary sees "no entry" (which is not 0)
GET  /memes/scores?ids=a,b,c    -> 200 [ { "memeId", "score" }, ... ] | 400 TOO_MANY_IDS
                                   the scores of the memes a client is SHOWING, at most 100 ids
                                   per call. An id that comes back has a real score (0 included);
                                   an id that does NOT come back has no tally to report — this
                                   service has no such meme. UIs must render those differently

# writes: Authorization: Bearer <security access token>, else 401 SIGN_IN_REQUIRED
#         (503 SECURITY_UNAVAILABLE when security itself could not be asked)
POST /memes                     multipart/form-data, field "file"
                                -> 201 { "id": "..." }, Location: /memes/{id}
                                   (uploading the same image twice returns the existing id)
                                   400 MALFORMED_MULTIPART for a body that is not a readable
                                   multipart; 413 TOO_LARGE past the size limit
POST   /memes/{id}/votes        { "direction": "UP" | "DOWN" }  -> 200 { "score": n } | 400 | 404
POST   /memes/{id}/tags         { "tags": [ ... ] }  — the author replaces the whole set
PUT    /memes/{id}/nsfw         { "nsfw": true|false }  — MODERATOR/ADMIN only
DELETE /memes/{id}              the author or a moderator takes the meme down

One vote per user per meme: voting again replaces your previous vote (never stacks).
Comment threads live in microservice-comments (GET/POST /memes/{id}/comments over there);
a deleted meme announces MEME_DELETED and the thread goes with it.
```

## Which runtime am I actually in?

Behaviour here is not one thing — several ports have interchangeable adapters, and predicting
what the service does means knowing which of them is wired. Worth checking before debugging:

| axis | modes | where |
|------|-------|-------|
| image bytes | `db` (default) / `filesystem` / `s3` | `MEMES_BLOB_STORE` — see the section at the bottom |
| who the caller is | introspection against security (`/me`) / offline JWT via its JWKS | `SecurityAuthenticationGate` vs `JwtSecurityAuthenticationGate` |
| events | real Kafka / `NoopMemeEvents` (nothing published, saga silent) | `KAFKA_ENABLED` |
| purge policy | the leaver's wizard choice > the admin override in the DB > the deployment default | `PurgeUserContent`, `GET/PUT /admin/purge-policy`, `PURGE_MEMES_POLICY` |
| "make it go away" | delete / NSFW blur (moderator flag) / hidden tombstone (comments' side) | three different mechanics, deliberately |

Environment variables are mapped to properties once, in `application.properties`
(`memes.upload.rate-limit-per-minute=${MEMES_UPLOAD_RATE_LIMIT:12}`) — the code reads the
property, deployments set the env var.

## Run & test

```bash
../mvnw -f pom.xml test                              # all module tests (JDK 25 + Spring Boot 3.5)
../mvnw -f pom.xml -pl memes-infrastructure spring-boot:run   # run the service (port 8083)
```

## Documentation — the living contract

The behaviour contract is the Gherkin specs in [`specs/`](./specs) — one file per use case
(upload, vote, tag, delete, flag-NSFW, admin-purge-policy, account-erasure), black-box over
HTTP, green in every build. [`Documentation.md`](./Documentation.md) is the same suite seen
from above: generated from the Allure reports of the test run — regenerate it with
`../create-documentation.sh` after `../mvnw clean test`.

## Where the image bytes live (`memes.blob-store` / env `MEMES_BLOB_STORE`)

Metadata is always in the database; the bytes sit behind the `ObjectStore` port with three
interchangeable adapters — `db` (default: a blob table, durability matches the metadata),
`filesystem` (`memes.blob-dir`), and `s3` (`memes.s3.endpoint/bucket/access-key/secret-key`,
`path-style` on by default — serves MinIO in the compose stack and real S3 alike; the bucket
is created at startup). Exactly one adapter bean exists per mode, pinned by
`BlobStoreSelectionTest`.

> Testcontainers note: the S3 adapter's round-trip test runs against a real MinIO and skips
> without docker. On Docker Engine ≥ 29 the bundled docker-java client may refuse to negotiate
> (`client version 1.32 is too old`) — fix once per machine with
> `echo "api.version=1.44" > ~/.docker-java.properties`.
