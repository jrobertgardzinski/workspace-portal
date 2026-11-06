# microservice-memes — Test Report & Documentation

Generated from Allure results by `build_documentation.py` on 2026-08-11. Behaviors below are **verified by passing tests** — rerun the suite, rerun this script, and the document cannot drift from the code.

## 📊 Execution Summary

| Module | Total | Passed | Failed | Broken | Skipped | Duration |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| memes-application | 52 | 52 | 0 | 0 | 0 | 206ms |
| memes-config | 9 | 9 | 0 | 0 | 0 | 48ms |
| memes-domain | 6 | 6 | 0 | 0 | 0 | 39ms |
| memes-image | 10 | 10 | 0 | 0 | 0 | 519ms |
| memes-infrastructure | 189 | 189 | 0 | 0 | 0 | 13.15s |
| memes-tags | 2 | 2 | 0 | 0 | 0 | 33ms |
| **TOTAL** | **268** | **268** | **0** | **0** | **0** | **13.99s** |

## 📝 Test Documentation (Behaviors)

This section describes the verified system behaviors based on passing tests.

### Epic: Config

#### Feature: Image limits

- **keeps a positive maximum dimension**
- **rejects a non-positive maximum dimension**

#### Feature: Purge rules

- **asText is the inverse of parse — the vocabulary round-trips**
- **popularity decides what a rule keeps**
- **speaks the shared vocabulary: DELETE, ANONYMIZE_AUTHOR, KEEP_POPULAR_ANONYMIZED:n**

#### Feature: Rate limit

- **after the window expires the same key starts a fresh count**
- **expired windows are evicted — the map does not remember every caller forever**
- **the ceiling is per key: one account is capped, another is free**
- **zero disables the guard**

### Epic: Contract

#### Feature: Meme deleted announcement

##### Story: Comments consumer

- **theAnnouncementShapeAndTopicTheThreadCascadeReliesOn(PactVerificationContext) microservice-comments - a meme deleted announcement for the comment thread**

##### Story: User-collections consumer

- **theAnnouncementShapeAndTopicTheCascadeReliesOn(PactVerificationContext) microservice-user-collections - a meme deleted announcement**

#### Feature: Pact skip guard

- **the cascade pact is where MemeDeletedPactProviderTest looks for it**
- **the saga pact is where PurgeConfirmationPactProviderTest looks for it**

#### Feature: Purge commands

- **erasesOnTheClosureAndAppliesTheRule(List)**
- **purgesWithTheDeploymentDefault(List)**
- **purgesWithTheLeaversChoice(List)**
- **restoresOnTheCompensation(List)**

#### Feature: Purge confirmation

- **theConfirmationShapeTheOrchestratorReliesOn(PactVerificationContext) microservice-offboarding - a user content purged confirmation**

#### Feature: Token introspection

- **aRecognisedTokenBecomesTheCaller(MockServer)**
- **anUnrecognisedTokenIsNobody(MockServer)**

### Epic: Data

#### Feature: Meme without its bytes

- **a moderator can delete it and flag it — moderation reaches every listed meme**
- **a stranger is still refused — the fix widens what works, not who may do it**
- **the author can delete it — the whole point: nobody's content may be undeletable**
- **the gallery lists it and its metadata answers — the row is what exists**
- **the picture itself is 404 — there is no picture, and that is the honest answer**

### Epic: Domain

#### Feature: Erasure mark

- **a fresh meme is ACTIVE and carries no mark**
- **a mark without its instant — or an instant without its mark — cannot be built**
- **a redelivered mark keeps the FIRST instant — the backlog measures an age**
- **marking reserves the meme and records when**
- **restoring a meme nobody marked is a no-op, not an error**
- **restoring puts it back exactly as it was, and twice is once**

### Epic: Executable specs

#### Feature: An account deletion is a SAGA, so hiding comes first and erasing comes last

- **A failure at another participant brings the leaver's memes back**
- **The PURGE command arriving twice, as Kafka promises it may, changes nothing**
- **The closure is the point of no return — the memes are erased for good**

#### Feature: Deleting a MEME

- **A GUEST may look, not delete**
- **A MODERATOR deletes anyone's MEME**
- **A stranger cannot delete someone else's MEME**
- **After a deletion not a byte of the MEME remains**
- **The author deletes their own MEME**

#### Feature: Flagging a MEME as NSFW

- **The NSFW flag is a MODERATOR's dial, and the gallery carries it**
- **The safe-for-work judgement is a MODERATOR's alone**

#### Feature: Tagging a MEME and finding it by TAG

- **Keyword spam is refused**
- **Only the author curates the TAGS**
- **The author's TAGS make the MEME findable — by those TAGS and no others**

#### Feature: The purge-policy default is an ADMIN's dial

- **A plain USER may not touch the dial**
- **Clearing the override restores the deployment default**
- **The ADMIN's override wins over the deployment default, and the purge obeys it**

#### Feature: Uploading a MEME

- **A GUEST may browse, not upload**
- **A file that is not an honest image is turned away, not crashed on**
- **A file that is not an honest image is turned away, not crashed on**
- **An uploaded MEME is public to fetch, thumbnail and all**
- **An uploaded MEME is public to fetch, thumbnail and all**
- **The gallery lists the MEME publicly**

#### Feature: Voting on a MEME

- **A GUEST may watch, not vote**
- **Repeating the same VOTE retracts it**
- **The MEME with more distinct up-voters ranks higher**

### Epic: Image

#### Feature: Object storage

- **a key that could escape the root is refused**
- **bytes round-trip by key, and a delete removes them**

#### Feature: Optimisation

- **a bomb that stays WITHIN the side limit but declares 16-bit RGBA is refused on its byte cost**
- **a decompression bomb — tiny file, huge declared dimensions — is refused before decoding**
- **a header we cannot interrogate is charged the worst case, not waved through**
- **a thumbnail of a transparent PNG stays transparent — the gallery tile is not a black square**
- **an opaque source (JPEG) stays flattened to RGB — no alpha channel is invented for it**
- **re-encoding strips embedded metadata — no EXIF (GPS, camera) survives an upload**
- **scales an over-sized image down so its longest side fits the limit**
- **the UPLOAD path keeps the alpha channel — a transparent PNG is not stored as black**
- **the cost of a pixel is read from the real layout: 3, 4 and 8 bytes, not a flat guess**
- **turns a BMP into a PNG, unchanged when within the limit**

### Epic: Infrastructure

#### Feature: Authentication gate

##### Story: MFA floor

- **a compliant moderator keeps every role**
- **an ordinary user is untouched either way — the floor binds only privilege**
- **an under-enrolled moderator is served as a plain USER**

##### Story: Offline JWT verification

- **a_properly_signed_token_carries_the_caller()**
- **an_under_enrolled_moderator_is_served_as_a_plain_user()**
- **an_unknown_kid_triggers_one_refetch_which_covers_key_rotation()**
- **forged_expired_or_foreign_tokens_are_refused()**

#### Feature: Batch scores

- **a batch bigger than a page of the wall is refused, not silently truncated**
- **a voted meme carries its score; an unvoted one carries a real, spoken-out zero**
- **an id this service has no meme for is missing from the answer, not reported as 0**
- **asking about nothing is an empty answer, not a failure**
- **the same meme asked about twice is answered once**

#### Feature: Decode concurrency

##### Story: Global decode ceiling

- **with every decode permit held, the next upload is refused 429 fast — and flows once permits free up**

##### Story: Interrupt vs overload

- **an interrupted permit wait refuses as UNAVAILABLE, not overload — with the interrupt flag restored**
- **the web boundary answers the interruption with 503, where the permit timeout earns 429**

#### Feature: Error responses

- **a store failure while serving a stored meme is a 500 with a generic body, not a 400**

#### Feature: Gallery paging

- **a nonsense page size is clamped, never taken literally**
- **an absurd page number is an empty page, not a 500**
- **the wall is walked page by page, and pages do not overlap**

#### Feature: Health probes

- **/actuator/health/liveness answers, and the lamp is deliberately NOT in it**
- **/actuator/health/readiness answers, and the listener lamp is in what it answers**
- **and the bare /actuator/health the compose stack probes still answers too**
- **the manifest probes the port the shipped properties actually put the actuator on**

#### Feature: Kafka configuration

##### Story: Consumer offset reset

- **the consumer really gets auto.offset.reset=earliest — a group with no committed offset must read history, not skip it**

##### Story: Producer clocks

- **the producer really gets max.block.ms=5s — the ceiling the non-blocking first attempt is measured against**

#### Feature: Listener health

##### Story: Heartbeat wiring

- **and that interceptor stamps the marker under the container's own id**
- **every registered container carries the record heartbeat**
- **the polling loop's heartbeat reaches the lamp — the registered id and the reporting one are not the same string**

##### Story: Lamp verdicts

- **a container Spring Kafka stopped is DOWN, and the details say it died abnormally**
- **a deployment without a broker is UP and says so — it takes no part in the saga**
- **a loop that stopped completing polls is DOWN — the stall the audit called invisible**
- **a running loop that keeps polling is UP — an idle topic is not a dead one**
- **a service still booting is not born unhealthy**
- **but a container that never reports at all goes DOWN once the tolerance is spent**
- **no listener containers where listeners are expected is DOWN: commands reach nobody**

##### Story: Probe group membership

- **and it is NOT a member of the liveness group — a dead listener is not a dead process**
- **the listener lamp is a member of the readiness group**

#### Feature: Meme deleted announcement

- **the announcement is keyed by the meme, so its whole cascade stays ordered**
- **the deletion announcement is published on the topic both consumers subscribe to**

#### Feature: Meme deletion

##### Story: After-commit deletes

- **a REQUIRES_NEW opened inside an after-commit callback parks the delete until ITS commit**
- **a REQUIRES_NEW that rolls back inside the callback drops its parked delete**
- **a delete issued from INSIDE an after-commit runnable executes — it must not park into the void**
- **a rollback drops the parked delete — the object stays with its restored row**
- **even an Error from one runnable neither cuts down the others nor escapes tx.execute**
- **inside a transaction the delete is parked until the commit, not run eagerly**
- **one after-commit runnable blowing up neither cuts down the next nor escapes tx.execute**
- **outside a transaction the delete runs immediately**
- **the pool hands connections back with autocommit ON — the after-commit sweeps depend on it**

##### Story: Blob cleanup

- **WebP and thumbnail variants cached DURING the delete transaction are swept after its commit**
- **deleteById removes the stored bytes AND the cached WebP and thumbnail variants**
- **deleteById tolerates a meme that never got a WebP variant**
- **exists() follows the row through save and delete — ServeMeme's orphan guard relies on it**

##### Story: Transactional teardown

- **a teardown that dies on its last DB step leaves nothing half-deleted**

#### Feature: Meme events outbox

##### Story: Republisher and retention

- **a fresh unpublished row is left alone — its first after-commit attempt may still be in flight**
- **a payload wider than the old varchar(1024) is stored intact — TEXT since V6, no silent cliff**
- **a retention of zero or less refuses the boot, naming the property and echoing the value**
- **commit + failed send: the republisher delivers the SAME event later and marks it published**
- **happy path: exactly one publication — the republisher does not double a marked row**
- **retention is batched and capped: a huge backlog goes 500 at a time, exactly as many batches as promised, the rest next pass**
- **retention: a delivered row past the threshold is reaped; a fresh delivered one and an old undelivered one stay**

##### Story: Transactional announce

- **a broker that never answers does not hold the deleting thread — the mark rides the ack callback**
- **a rolled-back delete transaction leaves NO outbox row and publishes NO event**
- **a send the broker never confirms leaves the row unpublished — the republisher's cue**
- **inside a transaction the event waits for the commit, then goes out and is marked published**
- **outside a transaction (no seam in sight) the event is published immediately**
- **the outboxed event still carries the cid of the request that deleted the meme**

#### Feature: Meme metadata privacy

- **an anonymous caller gets a masked uploader and own=false — never the address**
- **another signed-in user is not the owner, and learns nothing about who is**
- **deletion stays server-authorised: own=false does not stop a moderator**
- **the uploader is told the meme is theirs — that is what the Delete button needs**

#### Feature: Moderation gate

- **the author deletes their own; a stranger is refused; a moderator deletes anyone's**
- **the meta endpoint tells the caller whether the meme is theirs, so the UI can offer delete**

#### Feature: Object store

- **a key the active store already has is not overwritten — the active store is the truth**
- **a physical delete that FAILS after the commit stays owed**
- **a rollback takes the obligation with it — the object stays with its row**
- **an absent key is empty, not an error**
- **an obligation nobody discharged is finished by the sweep**
- **an obligation younger than the grace period is left alone — its transaction may still be open**
- **bytes round-trip by key; deletion leaves nothing behind**
- **memes.blob-store=filesystem selects the filesystem adapter, not the DB default**
- **one object that cannot be written is skipped, keeps its row, and costs no startup**
- **orphaned bytes move to the active store and the legacy table is emptied**
- **running it again does nothing — a startup task must survive every restart (ADR 0006)**
- **the obligation is committed WITH the deletion, and dropped once the bytes are gone**
- **with the DB store still active the table is left alone — there it IS the store**

#### Feature: Thumbnail cache

- **an orphaned thumbnail left by a crash is not served — the cache hit is gated on the meme still existing**
- **deleting the meme sweeps the cached thumbnail — and the endpoint answers 404, not a ghost image**
- **the favourites wall's request is not cacheable — it exists to find out whether the meme is still there**
- **the second GET is served from the cache — one decode ever, and Cache-Control invites the browser to keep it**

#### Feature: Twin services agreement

- **the_twins_carry_the_same_value_where_the_setting_means_the_same_thing()**
- **the_twins_fall_back_to_the_same_default_when_nothing_is_configured()**

#### Feature: UI runtime config

- **and never lets a browser cache them — an address change must survive a reload**
- **it serves compose's addresses, so an existing deployment sees no change**
- **it serves those instead — the whole point of the endpoint**

#### Feature: Upload and serve

- **an_uploaded_transparent_png_is_STORED_with_its_alpha_channel()**
- **corrupted_stored_bytes_surface_as_a_500_not_a_400()**
- **refuses_a_non_image_upload_with_400_not_500()**
- **refuses_a_truncated_image_upload_with_400_not_500()**
- **uploads_and_serves_an_optimized_meme()**

#### Feature: Upload guards

##### Story: Concurrent upload admission

- **only as many uploads hold bytes at once as the dial allows**
- **the permit is released even when the upload itself fails**
- **waiting too long is a 429, not a queue that grows without end**

##### Story: Declared-size refusal

- **a GET is never judged on its length, however it is announced**
- **a POST that declares more than the limit is refused with 413 and never reaches the chain**
- **an upload within the limit is none of this filter's business**

##### Story: Filter order

- **the size refusal runs before the sign-in gate, so an anonymous oversized POST is 413**

##### Story: Rate limit

- **a second upload in the window is refused with 429**

#### Feature: WebP negotiation

- **Accept: image/webp is served WebP and encoded only once**

### Epic: Saga

#### Feature: Marked meme is invisible

##### Story: Every public read

- **a marked meme is gone from every public read at once**
- **re-uploading the marked picture yields a NEW meme, never the leaver's id**
- **the compensation gives the gallery back exactly what the mark took**
- **the mark takes nothing away but the visibility — tags and votes survive it**

##### Story: SQL read guard

- **no query outside the erasure adapter reads the memes table directly**
- **the exemption is earned: the erasure adapter really does read the table**

#### Feature: Purge commands

- **a command type this participant does not know is ignored, not guessed at**
- **a command with a blank email is dropped the same way**
- **a command with no email is dropped: no purge, no confirmation**
- **a completed mark confirms the SAME saga it was commanded for — and erases nothing**
- **a completed mark is logged by saga id — the leaver's address never reaches the log**
- **a malformed command is logged by its size only — the payload carries an address**
- **a mark that fails confirms nothing and lets the failure out — so Kafka redelivers**
- **an unparseable rule is logged by shape and size — never quoted back from the wire**
- **the closure erases, and is NOT confirmed — the orchestrator has already decided**
- **the compensation restores, erases nothing and is not confirmed either**

#### Feature: Purge confirmation

##### Story: Outbox durability

- **a mark that throws writes nothing at all and lets the failure out to the container**
- **a rolled-back purge leaves NO confirmation — the row shares the erasure's fate**
- **a send the broker never confirms leaves the confirmation OWED — and the republisher pays it**
- **the confirmation goes out on memes-events, keyed by the saga, carrying the trace**

##### Story: Topic pin

- **and it is keyed by the saga run, which the outbox's 64-char key column can hold**
- **the confirmation is published on the topic the orchestrator listens to for this participant**

#### Feature: Purge retries

- **a store outage that does not pass: the retrying ends with the budget, not never**
- **a store outage that passes: the command is redelivered and the mark then happens**
- **the drop is counted and logged by coordinates — never by payload, which names the leaver**

##### Story: Budget arithmetic

- **a spent budget stops instead of retrying forever — that is the whole point**
- **blocking failures: fewer attempts, spread over minutes, because their time counts too**
- **every record gets its own deadline, not a share of a global one**
- **fast failures: the pauses double to the 15s cap and the whole budget is 90s of them**
- **the last pause is trimmed to the budget instead of overshooting it**
- **the service runs on the documented numbers, not on a test's**

#### Feature: Stuck erasure alarm

- **a register that cannot be read keeps its last value instead of reporting zero**
- **an empty backlog is the normal case: gauge at zero and not a word said**
- **an overdue mark is counted and said out loud — without naming the leaver**
- **the closure landing clears the alarm by itself — that is why it is a gauge**

### Epic: Tags

#### Feature: Tag value object

- **a tag is a search key, not free text — anything else is refused**
- **raw input is normalised: case and padding do not multiply tags**

### Epic: Use case

#### Feature: Cast vote

- **refuses to vote on a missing meme**
- **the library's toggle applies, anchored to an existing meme**

#### Feature: Idempotent commands

- **DECLARED EXCEPTION: the same vote twice TOGGLES — two calls undo, by design**
- **compensate: mark, then restore**
- **delete a meme**
- **delete a meme that is not there**
- **flag a meme NSFW (moderator)**
- **mark a leaver's gallery for erasure**
- **purge a leaver's gallery (default rule)**

#### Feature: Make thumbnail

- **a store that fails the sweep still answers 404, not 500**
- **a thumbnail cached while the meme was being deleted is removed by the re-check**
- **an orphaned thumbnail — the meme died in the crash window — is never served, and is swept on the way out**
- **makes a small PNG thumbnail of a stored meme**
- **no thumbnail for a missing meme**
- **the orphan's bytes are never read — the meme row is asked first, the store second**
- **the second request is served from the {id}.thumb cache — one decode, ever**

#### Feature: Publish meme

- **a failed save releases the content claim — no ghost for future identical uploads**
- **a save that dies AFTER uploading the bytes leaves no orphaned blob behind**
- **publishes an optimized meme**
- **publishing the same image twice reuses the meme (dedup)**
- **two simultaneous uploads of the same picture store exactly one meme**
- **when the claim compensation ALSO fails, the original failure surfaces and the blob still goes**

#### Feature: Purge user content

- **KEEP_POPULAR: the community's favourites survive anonymised, the rest goes**
- **KEEP_POPULAR: the leaver cannot vote for his own survival**
- **a closure that arrives without a mark erases nothing**
- **a meme the rule KEEPS comes back to the gallery anonymised, not hidden for ever**
- **every command arrives twice: marking, erasing and restoring are all idempotent**
- **every vote the leaver cast is retracted**
- **the admin's override beats the deployment default**
- **the compensation puts the leaver's memes back exactly as they were**
- **the leaver's memes disappear with their votes; the thread owner is told**
- **the leaver's wizard choice beats the admin's override**
- **the mark alone destroys nothing — that is what makes the saga compensatable**

#### Feature: Rank memes

- **a meme the store has no publication time for is treated as brand new, not buried**
- **hot cools with age: a fresh contender outranks last week's champion**
- **ranking a page costs ONE read of the store, not one per comparison**
- **the hot page is capped at TOP_N — the hottest ones, not everything ever voted on**
- **with equal ages the plain score still decides**

#### Feature: Serve meme

- **a WebP cache hit reads no stored image at all — the row answers, the cache serves**
- **a WebP cached for a meme deleted mid-flight is removed again — no orphan**
- **a failed cache write does not cost the caller their WebP**
- **a meme whose bytes are gone from the active store serves nothing — but still exists**
- **the encoded WebP is cached for the next request (happy path)**

#### Feature: Show meme scores

- **REGRESSION: the capped hot ranking cannot answer for a wall that outgrew it**
- **a meme nobody voted on scores 0 — and SAYS so, with a key of its own**
- **a whole page of tiles costs one read of each store, not one read per tile**
- **an id this service has no meme for is ABSENT — never a zero**
- **asking about nothing reads nothing**
- **ballots that outlived their meme do not resurrect it into the answer**

#### Feature: Tagging and search

- **only the uploader tags their meme; ghosts and spam are refused**
- **search narrows the gallery to the tag, in gallery order, existing memes only**
- **the author curates the whole tag set in one move**

### Epic: Voting

#### Feature: Ballots and their memes

- **a meme row deleted by ANY path takes its ballots with it — the schema guarantees it**
- **a vote that loses the race with a delete casts nothing, rather than an orphan**
- **an orphan ballot cannot be written at all — not even straight through SQL**

