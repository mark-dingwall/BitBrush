# Username PIN Recovery Design

## Summary

BitBrush will require each new user to choose a case-sensitive, four-character PIN when creating a username. If the browser's BitBrush identity is later removed from `localStorage`, the user can recover the private UUID by submitting the username and PIN. Routine reconnects continue to use the locally stored private UUID and do not store or resend the PIN. Public canvas payloads use a separate author ID so the bearer UUID is not disclosed.

The PIN is a lightweight recovery credential for this anonymous canvas, not a replacement for the application's UUID-based identity model. The implementation will nevertheless protect it with canonical Unicode handling, a secret pepper, per-user salts, Argon2id, Turnstile, throttling, generic failures, and careful secret handling.

## Goals

- Let a user recover the private UUID associated with a username after losing local browser state.
- Preserve pixel authorship and, for post-migration identities, any live in-memory banking state by restoring the same private UUID.
- Separate the private bearer UUID from the public author identifier exposed by canvas APIs.
- Avoid persistent plaintext PIN storage on either the server or client, except for the explicitly requested operator export.
- Support case-sensitive Unicode PINs through one server-authoritative canonicalization implementation.
- Backfill every existing user with a non-null hashed PIN and produce a one-time protected export of the generated PINs.
- Preserve the independently embeddable, dependency-free widget.
- Maintain BitBrush's strong automated test coverage, with special attention to Unicode, credential handling, throttling, concurrency, migrations, and error behavior.

## Non-goals

- Full authentication, durable login sessions, or replacing UUID bearer identity.
- Email-based or self-service PIN reset.
- An administrator PIN-reset HTTP endpoint.
- Storing the PIN in `localStorage`, cookies, logs, URLs, or response caches.
- Retrofitting users with a user-selected PIN during rollout. Existing users receive generated backfill PINs.
- Shared throttling across multiple application instances. BitBrush currently runs one Fly machine; shared state can be introduced if the service scales horizontally.

## Existing behavior

Both clients store a generated UUID and username in `localStorage`. At startup they silently submit both values to `POST /api/users`. The endpoint treats an unknown UUID as registration and an existing UUID as reconnection. Existing UUIDs bypass a new Turnstile check and are re-added to the in-memory verified set. The same UUID is used as the STOMP principal, pixel-placement credential, database user key, persisted pixel author key, and public author identifier.

There is no PIN, recovery operation, identity lookup, cookie, durable session, or bearer token other than possession of the UUID. Losing local storage therefore loses access to the original identity. Pixel-info and broadcast responses publicly expose that UUID, so existing UUID values cannot safely remain bearer credentials after PIN recovery is introduced.

## User experience

The identity modal has two explicit modes, following a conventional create/login pattern.

### Create account

The default view contains:

- Username.
- PIN.
- Confirm PIN.
- A primary **Create account** button.
- A smaller **Already have one? Log in** action.

The server compares the two PIN fields after canonicalization. A successful response stores only the confirmed UUID and authoritative username in the client's existing local-storage keys. The PIN fields are cleared as the modal closes.

### Log in / recover

The recovery view contains:

- Username.
- PIN.
- A primary **Log in** button.
- A smaller **Need an account? Create one** action.

Successful recovery stores the account's current private UUID and authoritative username, closes the modal, and then starts WebSocket initialization. Failures leave the form open and clear the PIN.

PIN fields use password masking and appropriate `autocomplete` values. They do not use HTML `maxlength=4`, because HTML counts UTF-16 code units and would reject some valid four-code-point PINs. The clients treat PINs as opaque strings and render server validation errors; they do not duplicate the Unicode security rules.

### Routine reconnect

Whenever a UUID is present, even if the username entry is missing, the client sends the UUID to the reconnect endpoint. The authoritative username in the response replaces the locally stored username. The PIN is neither needed nor available. If the UUID is unknown, such as after a development database reset, the client removes the stale local identity and opens the Create/Log-in modal. A username without a UUID is also discarded as stale.

The client no longer creates and persists a UUID merely because the page loaded. It generates a UUID only when the user submits Create account; recovery supplies the existing UUID. This avoids leaving an unrelated UUID in storage while the user is trying to recover an identity.

For both clients, identity resolution is a promise that settles only after reconnect succeeds or the user completes Create/Log in. The STOMP client is constructed and activated only afterward, so its CONNECT headers always contain the authoritative UUID. The widget changes its current connect-before-identity order accordingly.

## API design

### Create a user

`POST /api/users`

Headers:

```text
Content-Type: application/json
X-Turnstile-Token: <token>
```

Body:

```json
{
  "uuid": "browser-generated-uuid",
  "username": "artist",
  "pin": "A😀b!",
  "pinConfirmation": "A😀b!"
}
```

The endpoint always represents creation. It enforces the identity-request body cap, requires the client-generated private ID to be a canonical UUID string, validates the request, requires Turnstile, rejects an existing username or a private UUID found in either identifier column, assigns a random public author ID from the syntactically disjoint `author_` plus base64url namespace, hashes the canonical PIN, commits the user, and only then marks the UUID verified. Success returns `201 Created` with the authoritative identity and no PIN.

Creation names the database constraints for private UUID, username, and public author ID. A pre-check provides normal errors, while the constraints decide concurrent races. Only a violation of the named public-author-ID constraint triggers generation of another ID and a new `TransactionTemplate` attempt; the failed transaction is never reused. Named private-UUID and username violations map to `409`, and unrelated integrity failures propagate. Retries are bounded and only the successfully committed attempt is marked verified.

Duplicate UUIDs or usernames return `409 Conflict`. Validation failures return Spring's RFC 7807 `400 Bad Request` response. Turnstile failure remains `403 Forbidden`.

### Reconnect a stored identity

`POST /api/users/reconnect`

Body:

```json
{
  "uuid": "stored-uuid"
}
```

On success, the endpoint marks the UUID verified and returns:

```json
{
  "uuid": "stored-uuid",
  "username": "artist"
}
```

An unknown UUID returns the existing `404 User Not Found` problem. This operation intentionally preserves today's lightweight possession-of-UUID behavior and does not require a PIN or Turnstile challenge.

### Recover an identity

`POST /api/users/recover`

Headers:

```text
Content-Type: application/json
X-Turnstile-Token: <fresh-token>
```

Body:

```json
{
  "username": "artist",
  "pin": "A😀b!"
}
```

On success, the endpoint clears the username's failed-attempt state, marks the recovered UUID verified, and returns the same identity response as reconnect. The response includes `Cache-Control: no-store`.

An unknown username and an incorrect PIN both return the same `401 Unauthorized` RFC 7807 response with a generic title and detail. The implementation performs a dummy Argon2id verification for an unknown username so the two cases have comparable work and timing. A malformed PIN remains a `400` validation error because it reveals only the public input contract, not account existence.

Username matching remains exact and case-sensitive, preserving the current database semantics in which differently cased usernames can be distinct.

Rate-limited recovery returns `429 Too Many Requests` with `Retry-After`. Turnstile failure remains `403 Forbidden`. All create, reconnect, and recovery responses containing a private UUID include `Cache-Control: no-store`. Responses and logs never contain the submitted PIN.

### Public authorship payloads

`PixelInfoResponse` and `PixelBroadcast` replace public `authorUuid` fields with `authorId`. The client uses this opaque public value for tooltip/highlight cache invalidation exactly as it used the old field. Pixel placement still submits the private UUID, but `PixelService` resolves the user and persists/broadcasts that user's public author ID. The private UUID is never returned by public canvas or statistics endpoints. Legacy public IDs remain UUID-shaped to preserve pixel history; new public IDs use `author_` followed by at least 128 bits of cryptographically random base64url data, so they cannot equal a canonical private UUID.

### Request-size boundary

A small `OncePerRequestFilter` applies only to the three identity POST endpoints and rejects request bodies larger than 4 KiB with an RFC 7807 `413 Content Too Large` response before JSON deserialization, Turnstile, or Argon2. It rejects an excessive `Content-Length` immediately. For unknown-length/chunked input, the filter itself reads at most 4,097 bytes into a bounded buffer; it writes the `413` response on overflow or passes a replayable request wrapper containing the verified-at-most-4-KiB body to MVC. DTO field limits remain as defense in depth and provide normal `400` validation errors for small malformed requests.

## Component boundaries

### `UserController`

The controller exposes the three identity operations, resolves HTTP request metadata such as source IP, delegates each complete use case to `UserIdentityService`, and maps results to HTTP responses. It does not directly sequence Turnstile, throttling, persistence, hashing, or verification-cache updates.

### `UserIdentityService`

This service is the single orchestration owner for the HTTP creation, UUID reconnection, and username-recovery workflows, including their Turnstile calls, throttling, repository coordination, and post-success verification updates. Its create/recover methods receive the token and, for recovery, the resolved source IP. User-registration behavior moves out of `PixelService`, returning that class to canvas and pixel responsibilities.

The service never accepts or returns a raw PIN beyond the duration of a create or recover call. It never logs one. Creation uses a short explicit `TransactionTemplate` operation that saves and flushes the complete user row; only after that operation returns, and therefore after commit succeeds, does it call `markVerified`. The service itself is not wrapped in a broader transaction that could defer the commit past that call.

### `PinCredentialService`

This service is the sole application implementation of PIN canonicalization, hashing, and verification. It also owns a small global semaphore covering real hashes and dummy verification so concurrent memory-hard work cannot exhaust the production VM. The migration reuses the same lower-level credential codec so rollout hashes cannot diverge from runtime hashes.

### `RecoveryAttemptService`

This service owns bounded, expiring, in-memory counters. It uses an injected `Clock` so expiry is deterministic in tests. Updates are atomic so simultaneous recovery attempts cannot exceed a limit through races.

### `ClientIpResolver`

In production, the resolver obtains the public caller from Fly's `Fly-Client-IP` header. In profiles not hosted behind Fly Proxy it uses the servlet request's socket address. It validates and canonicalizes IPv4/IPv6 values rather than trusting arbitrary strings.

### `LegacyPinExportRunner`

This startup-only component is disabled unless `PIN_BACKFILL_EXPORT_PATH` is set. It runs after Flyway has committed, reproduces only credentials marked `pin_backfilled=true`, writes the protected export, and then exits. Keeping export I/O outside the migration is required for database/filesystem failure recovery.

### Existing services

`TurnstileService` remains the only Cloudflare integration and owner of the process-lifetime verified set. `UserIdentityService` makes creation and recovery call `verify(token)`, performs the database/credential operation, and calls `markVerified(uuid)` only after that operation succeeds. This ordering prevents a failed database write or failed credential check from leaving a UUID verified. HTTP reconnect calls `markVerified(uuid)` only after repository lookup succeeds; the STOMP connection-frame interceptor does the same after its own synchronous lookup. Disconnect no longer removes verification.

The existing `verifyAndRemember` behavior is therefore decomposed into its existing `verify` and `markVerified` operations where transaction ordering matters rather than duplicated in a new Turnstile implementation.

The two static clients reuse their existing Turnstile rendering, wait, reset, and header helpers. Their small identity UI implementations remain mirrored because `bitbrush-widget.js` must stay independently embeddable.

### WebSocket lifecycle

The existing inbound `ChannelInterceptor.preSend` remains the STOMP authentication boundary. Spring accepts both `StompCommand.CONNECT` and the alternate `StompCommand.STOMP` as connection frames, so the interceptor handles them identically. For either command it synchronously validates the canonical private UUID with a side-effect-free `users.uuid` lookup before the inbound executor is invoked. An absent, malformed, public-author, unknown, or lookup-failing UUID throws a generic messaging exception, causing Spring's STOMP handler to reject and close the connection without disclosing the submitted value. For a known UUID the interceptor calls `setUser` and `markVerified`; Spring saves that principal and associates it with subsequent messages in the session. The interceptor does not call the HTTP reconnect workflow.

Marking verification at this point is intentionally independent of whether the broker later completes CONNECT. Verification is process-lifetime admission state, not WebSocket-presence state, and possession of the same registered private UUID can already restore it through `/api/users/reconnect`. Once creation, recovery, HTTP reconnect, or a validated STOMP CONNECT marks a UUID verified, it remains so until process restart. This lets an automatic STOMP reconnect after a server restart restore placement authorization without a PIN or extra HTTP request. The set is bounded by registered identities observed during that process lifetime.

`SessionConnectedEvent` remains the presence boundary: Spring publishes it while processing the broker's outbound CONNECTED frame, before delivering that frame to the socket, and the session is then fully established. `WebSocketEventListener` uses the event's authenticated `Principal` to ensure that user's bank entry exists and retains its existing idempotent session-ID set for the online-session count. On disconnect it updates only that count; it does not mutate verification or banking presence.

`BankingService` removes its custom UUID-to-session mappings and uses Spring's existing `SimpUserRegistry` as the source of connected presence. The registry already tracks multiple sessions for one principal, removes sessions idempotently, and removes a user only after the last session disconnects. Each earn tick iterates the registry's unique users and performs one existing atomic balance `compute` per UUID, so multiple tabs or recovered devices still earn only once. Balance state remains in `bankMap`, `getInitialState` initializes it defensively if absent, and `convertAndSendToUser` continues to fan updates out to every session for that principal.

If a final disconnect and an earn tick are concurrent, either may observe the boundary first; after the disconnect event has completed, subsequent ticks do not earn for that user. Preventing a single boundary tick under every interleaving would require custom cross-component locking without protecting credentials or durable value, so it is deliberately outside the MVP.

## PIN canonicalization

The server alone implements this canonicalization algorithm:

1. Apply Unicode NFC normalization.
2. Replace every code point in Unicode general categories `Cc` (control), `Cf` (format), `Zs` (space separator), `Zl` (line separator), and `Zp` (paragraph separator) with U+0020 SPACE.
3. Reject unpaired UTF-16 surrogates; they are not valid Unicode scalar values and cannot represent valid UTF-8 input.
4. Require exactly four Unicode code points after replacement.
5. Preserve case and all remaining code points exactly.
6. Encode the result as UTF-8 for credential processing.

Spaces are significant and are not trimmed. Canonicalization occurs before comparing the Create and Confirm PIN fields. The server limits each raw field before doing expensive work, preventing an oversized value from being used as a resource-exhaustion input. Browser clients send the exact strings entered and display the returned validation problem.

## Credential storage

The `users` table gains a `pin_hash` column. Its final schema is `NOT NULL`; plaintext PIN and salt columns are not added.

For each credential:

1. Canonicalize the PIN.
2. Compute HMAC-SHA-256 over a fixed `bitbrush-pin-v1` domain prefix and the canonical UTF-8 bytes with a server-wide secret pepper.
3. Encode the HMAC output in an unambiguous fixed representation.
4. Feed that value to Argon2id with a new cryptographically random per-user salt.
5. Store the standard encoded Argon2id string containing algorithm version, work parameters, salt, and hash.

The application uses a maintained Java implementation rather than implementing Argon2 itself. Production parameters meet or exceed current OWASP guidance and are calibrated against the 512 MiB Fly machine before release. Tests may inject cheaper parameters where they are testing orchestration rather than the production work factor.

All Argon2 operations acquire a fair, process-wide semaphore. The production default is two concurrent operations on the 512 MiB machine and is confirmed during calibration. A request that cannot acquire a permit immediately fails with `503 Service Unavailable` and a short `Retry-After`; it does not occupy a servlet thread waiting. Permit release is guaranteed in `finally`. This bound applies equally to creation, recovery, and dummy unknown-user checks.

Salting prevents precomputed/rainbow-table comparison and prevents identical PINs from producing identical stored values. Argon2id raises the cost of each offline guess. The pepper is stored separately from PostgreSQL and prevents a database-only attacker from validating guesses. A four-character user-chosen secret still has limited entropy; the design does not claim resistance after a complete application-and-database compromise.

`PIN_PEPPER` is a required production/docker secret and represents at least 32 cryptographically random bytes in a documented encoding. Development and test profiles use explicit non-production values. Startup fails on missing or malformed production configuration. Losing or changing the pepper makes existing PIN hashes unverifiable, so backup and rotation limitations are documented.

## Recovery throttling

Default limits are configurable but secure by default:

- Five recovery failures per exact username in a rolling 15-minute window.
- Twenty recovery requests per source IP in a rolling 15-minute window.

The IP check occurs before calling Turnstile. Account-specific state is created only after a valid Turnstile challenge, limiting attacker-driven map growth from arbitrary unauthenticated usernames. The account map is also size-bounded and entries expire; when capacity cannot be safely allocated, recovery fails closed.

Unknown and existing usernames consume equivalent account attempts after Turnstile. A successful recovery clears that username's failure state; it does not erase the source IP's request history. Every `429` response reports the applicable remaining delay through `Retry-After` without revealing which limit fired.

The counters are intentionally instance-local, matching BitBrush's current single-instance deployment and existing in-memory banking/verification architecture. Horizontal scaling requires shared throttling before adding instances.

## Database migration and legacy users

Flyway adds `users.author_id`, `pin_hash`, and `pin_backfilled`, and changes the pixel author column's name from `author_uuid` to `author_id`. Renaming the pixel column preserves its values and does not update or rewrite the append-only pixel log. A Java migration then:

1. Copies each legacy user's old UUID to its public `author_id`, matching the unchanged author values already present on that user's pixels.
2. Replaces each legacy user's `users.uuid` with a new cryptographically random canonical UUID, retrying if it appears in either the private-UUID or public-author-ID column. Previously exposed UUIDs are therefore no longer accepted by reconnect, STOMP, or pixel placement after deployment.
3. Derives a stable pseudorandom four-digit PIN from HMAC-SHA-256 over the `bitbrush-legacy-pin-v1` domain prefix plus the stable public `author_id`, keyed by `PIN_PEPPER`. Rejection sampling maps the HMAC output uniformly into `0000`–`9999`.
4. Hashes that derived PIN through the same runtime PIN credential pipeline, using a new Argon2 salt.
5. Updates the row and marks `pin_backfilled=true`.
6. Adds uniqueness and `NOT NULL` constraints for `author_id` and `pin_hash` after every legacy row is populated. New application-created users receive unique `author_…` public IDs and always store `pin_backfilled=false`.

The deterministic derivation is used only for legacy backfill; user-selected PINs are never derivable. It makes the legacy plaintext mapping reproducible without storing it in PostgreSQL and makes migration retry safe even though Argon2 salts and encoded hashes can change after a rolled-back attempt. The stable `author_id`, rather than the rotated private UUID, is the derivation input so the runner can reproduce the PIN without retaining the old credential separately.

Rotating legacy UUIDs is an intentional one-time compatibility break required because current public APIs have disclosed them. Existing browser storage becomes stale and opens the identity modal; the user logs in with the operator-delivered backfill PIN and receives the new private UUID. The username and all historical authorship remain unchanged. A deployment restart already clears the in-memory bank, so no additional durable balance is lost.

No filesystem publication occurs inside the Flyway transaction. After Flyway commits, a small `LegacyPinExportRunner` runs during application startup only when `PIN_BACKFILL_EXPORT_PATH` is configured. It queries `pin_backfilled=true` users, reproduces their PINs from `author_id`, and builds the complete expected `username<TAB>PIN` byte sequence in deterministic username order. Usernames containing tab, CR, or LF are rejected by existing validation and are checked again before export. The configured file must live in a dedicated sibling directory such as `/tmp/bitbrush-pin-backfill/export.tsv`; the runner creates that previously absent directory with `0700`, or on restart validates that it is a real directory owned by the process user with exactly that mode.

If the target does not exist, the runner removes only recognizable stale runner temporaries inside that validated private directory, creates a fresh temporary file there with `CREATE_NEW` and owner-only `0600` permissions, writes and fsyncs it, then publishes without replacement by atomically creating a hard link at the target. Link creation fails if any target already exists; an absence check is not the safety mechanism. The runner fsyncs the directory and unlinks the temporary name. If the filesystem cannot provide same-filesystem hard links and directory fsync, startup fails rather than weakening the guarantee. On any failure it cleans up only the temporary it created in that invocation and fails startup.

If the target already exists, the runner requires it to be a regular non-symlink file owned by the process user with `0600` permissions and byte-for-byte identical expected content; a valid prior export is accepted and stale runner temporaries in the private directory are removed, while any mismatch or unsafe file fails startup without overwrite. It logs only the path, row count, and Fly machine identifier.

If startup or the machine fails before the export is retrieved, the operator can restart with the same pepper and path; the runner reproduces the same username/PIN mapping from durable public author IDs. The ephemeral file is therefore a delivery copy, not the only copy. A fresh database has no `pin_backfilled=true` rows and produces no export.

On Fly.io the configured path should be under a dedicated directory in `/tmp`, for example `/tmp/bitbrush-pin-backfill/export.tsv`. The operator retrieves it over SSH and deletes it promptly. Fly's ephemeral filesystem is not treated as a durable backup.

## Error handling and privacy

New domain exceptions are handled centrally as RFC 7807 problems, following existing project conventions:

- Duplicate username/UUID: `409 Conflict`.
- Invalid credentials: `401 Unauthorized` with one generic response.
- Recovery throttled: `429 Too Many Requests` plus `Retry-After`.
- PIN hashing capacity exhausted: `503 Service Unavailable` plus `Retry-After`.
- Turnstile rejected: existing `403 Forbidden` response.
- Unknown reconnect UUID: existing `404 User Not Found` response.
- Invalid request or canonical PIN: `400 Bad Request`.

Every identity response containing a private UUID uses `Cache-Control: no-store`. Raw private UUIDs are credentials and never appear in application logs, exception messages, RFC 7807 details, metrics labels, or tracing attributes; existing controller, WebSocket, banking, pixel-service, and `UserNotFoundException` logging is removed or changed to non-secret session IDs, counts, or public author IDs. PINs and hashes are likewise never logged. Username logging is limited to what is operationally necessary, and failures do not distinguish account existence. The temporary post-migration export is the only plaintext-at-rest exception, is explicitly requested, is reproducible, has restrictive permissions, and is operator-deleted after retrieval.

## Testing strategy

PIN recovery changes identity and production schema, so tests are deliverables rather than follow-up polish. Coverage includes behavior, negative paths, boundaries, concurrency, and deployment migration.

### PIN credential unit tests

Parameterized tests cover:

- Four ASCII code points.
- Supplementary-plane emoji that occupy two UTF-16 units but one code point.
- Mixed scripts and punctuation.
- Case-sensitive success/failure pairs.
- NFC composed/decomposed equivalence.
- Every replaced category (`Cc`, `Cf`, `Zs`, `Zl`, `Zp`).
- Significant leading/trailing U+0020 spaces.
- Values that become too short or long only after canonicalization/replacement.
- Unpaired high and low surrogates.
- Empty, null, oversized, three-code-point, and five-code-point values.
- Confirmation comparison after canonicalization.
- Same PIN producing different encoded hashes through unique salts.
- Correct verification and failure for wrong PIN, wrong case, or wrong pepper.
- Stored strings containing neither raw PIN nor reusable pepper material.
- Encoded hashes carrying the expected Argon2id algorithm and work parameters.
- Domain separation between ordinary PIN peppering and deterministic legacy PIN derivation.
- Global Argon2 permit exhaustion, interruption, and guaranteed permit release after success or exception.

### Throttling unit and concurrency tests

With an injected fake clock, tests cover both limits immediately below, at, and after their boundaries; rolling-window expiry; successful account reset; unknown-account accounting; IP canonicalization; map-capacity fail-closed behavior; and independence between usernames/IPs.

Concurrent tests release many workers through a barrier and prove that atomic updates never permit more successful checks than configured. Cleanup is exercised concurrently with checks to catch unsafe iteration or lost updates.

Credential-concurrency tests prove that no more than the configured number of real or dummy Argon2 operations overlap across mixed create/recovery workloads.

### Service tests

`UserIdentityService` tests cover:

- Successful creation persists only a hash, assigns a distinct public author ID, commits, and then marks the private UUID verified.
- Duplicate private UUID and duplicate username behavior, including rejection when a requested private UUID equals a legacy public author ID.
- Canonical private-UUID validation, disjoint new public-ID syntax, and a forced public-ID collision that retries in a fresh transaction.
- Concurrent private-UUID and username constraint races return `409`; only the final successful public-ID attempt marks verification, and unrelated integrity violations are not misclassified.
- Repository, flush, and commit-time failures do not mark a UUID verified.
- Reconnect returns the authoritative username and never mutates it.
- Unknown reconnect UUID.
- Recovery returns the associated private UUID and resets only the appropriate account counter.
- Incorrect PIN, unknown username, and dummy-hash execution.
- Turnstile/credential failure never marks a UUID verified.
- Security steps occur in the documented order within the single service-owned workflow.
- Pixel placement translates a private UUID to its public author ID; public responses never contain the private UUID.
- Concurrent creation retains database uniqueness guarantees.

### Controller slice tests

MockMvc slice tests cover all three contracts, missing/malformed bodies, Unicode JSON, source-IP resolution, delegation to the single service-owned workflow, every expected status, generic problem details, `Retry-After`, `Cache-Control: no-store`, and absence of PIN/hash response fields. Filter tests prove both declared `Content-Length` and chunked/unknown-length bodies stop at 4 KiB with `413` before controller, Turnstile, or credential work, while an exact-limit body reaches normal validation.

Captured-log tests exercise create, reconnect, recover, failed reconnect, STOMP connect/disconnect, and pixel placement at the most verbose supported application log level. They assert that the private UUID, PIN, hash, and pepper are absent from both logs and rendered problem details.

### Repository and application integration tests

Repository tests verify hash persistence, exact username lookup, private-UUID and public-author-ID uniqueness, and non-null enforcement. Full Spring tests execute create → reconnect and create → recover → place-pixel request sequences, proving the recovered private UUID works through the existing placement and verification path while public pixel payloads expose only the public author ID.

WebSocket integration tests verify the Spring-native lifecycle directly for both `CONNECT` and the alternate `STOMP` connection command. Known private UUIDs receive CONNECTED with the expected Principal; absent, malformed, public-author, unknown, and lookup-failing UUIDs receive no CONNECTED frame and are never marked verified. Raw-frame coverage exercises an invalid `STOMP` handshake followed by a pipelined SEND, proving that neither a registry session, online-count change, bank state, nor pixel broadcast is produced.

Clearing the verification cache and then connecting a known UUID proves automatic STOMP reconnect restores placement authorization. A test blocks downstream connection handling after successful UUID validation and asserts verification is already set even though no `SimpUserRegistry` session or banking presence exists. A normal disconnect test asserts that process-lifetime verification remains set afterward.

The first `/app/bank` response after CONNECTED reflects the retained balance. The multi-session integration test uses a test-safe long scheduled interval and invokes `earnPoints()` explicitly. It waits for both subscription receipts and a registry state of one user with two sessions, drains initial messages, performs one tick, and asserts exactly one fresh `balance + 1` update reaches each session with no extra update. It then disconnects one session, waits until the registry shows exactly one remaining session, performs another explicit tick, and asserts that only the survivor receives exactly one next increment. After the final disconnect event completes, a subsequent tick does not earn. Duplicate disconnect events remain harmless. Banking unit tests mock `SimpUserRegistry` snapshots to cover no sessions, one session, multiple sessions for one user, multiple users, and disconnect-between-ticks without recreating Spring's session state in application code.

All existing tests that create users are updated deliberately: creation tests provide PINs; tests concerned only with registered users use service fixtures or the appropriate identity operation. This prevents permissive compatibility shortcuts from hiding missing credentials.

### Migration tests

A PostgreSQL Testcontainers test starts from the V1 schema, inserts representative legacy users, and runs the real Flyway migration. It verifies:

- Every legacy row has a non-null, distinct, valid Argon2id hash.
- Every legacy row has `pin_backfilled=true`; newly created rows have `pin_backfilled=false`.
- Each old public UUID is retained as `author_id`, each private `users.uuid` is rotated, and old UUIDs no longer authenticate.
- Creation rejects a private UUID equal to any legacy `author_id`; new `author_…` IDs cannot parse as private UUIDs, and generated-ID database collisions are retried.
- Existing pixel rows are not rewritten and still resolve through their renamed `author_id` to the correct username.
- Each deterministically derived PIN verifies for its matching row with the configured pepper.
- Derived legacy PINs are stable four-digit decimal values produced without modulo bias and change when the author ID, domain prefix, or pepper changes.
- The final database enforces `NOT NULL` for private UUIDs, public author IDs, and PIN hashes, plus uniqueness for both identifiers, with direct failing inserts rather than relying only on Hibernate metadata.
- The TSV escapes/represents every currently valid username safely.
- The TSV contains exactly the `pin_backfilled=true` usernames once each and no newly created user.
- The export has `0600` permissions on supported test hosts.
- PINs and pepper do not appear in Flyway/application logs captured by the test.
- Flyway performs no filesystem writes before its transaction commits.
- Post-commit export validates the private directory and accepts an existing byte-identical owned `0600` regular file; it rejects mismatched content, symlinks, wrong ownership/modes, and unwritable paths without overwriting them.
- A deterministic publish-race test creates the target after preparation but before hard-link publication, proves publication fails without changing that target, and then proves a clean retry succeeds.
- Interrupted writes clean up only the runner-owned sibling temporary artifact; a stale such artifact is safely replaced on retry.
- Export failure leaves committed hashes intact and can reproduce the identical PIN mapping on a later startup.
- Deleting an ephemeral export and rerunning the exporter reproduces identical content.
- An empty V1 database migrates and starts without an export path.
- Re-running Flyway is idempotent and does not replace committed hashes.
- The resulting schema passes Hibernate `validate` under the production database dialect.

The migration/full-application test also treats one migrated legacy UUID-shaped `author_id` as an adversarial credential across every bearer path: reconnect returns the generic unknown-user `404` without verification, pixel placement fails without writing even when the normal verification boundary is otherwise satisfied, and both STOMP connection commands produce no CONNECTED, banking presence, or `/app` invocation. Recovery with that row's derived PIN returns the rotated private UUID, and only that value then succeeds through reconnect, placement, and STOMP.

The container test is marked to skip only when Docker is genuinely unavailable locally. CI includes an explicit migration-test task and fails if that task is skipped, ensuring GitHub Actions executes the PostgreSQL path. Unit tests still exercise migration/backfill collaborators without Docker.

### Client verification

Because the static clients intentionally have no application build step, verification includes:

- JavaScript syntax checks.
- A separate local Playwright configuration that serves the static resources, substitutes deterministic Turnstile/API boundaries, and tests the full-page client and standalone widget before deployment.
- Automated browser coverage for create/recover mode switching, opaque Unicode field submission, server-side confirmation/validation error rendering, identity-before-STOMP ordering, stale UUID handling, and the exact local-storage keys and values retained after success.
- Canvas-response tests assert the clients use public `authorId` for cache invalidation and never receive or render the private UUID.
- Local browser smoke tests for create, mode switching, confirmation mismatch, Unicode PIN feedback, recovery, stale UUID handling, storage contents, Turnstile refresh, and both full-page and widget clients.
- Production Playwright setup reads a provisioned `BITBRUSH_E2E_UUID` secret, stores only that UUID, and lets `/api/users/reconnect` supply the authoritative username. It fails fast when the secret is absent and never embeds a PIN or reusable UUID in source control.

No automated test writes real PINs, hashes, or pepper values to test output.

## Verification and rollout

Before merge:

1. Run the focused credential, identity, controller, repository, throttling, and migration tests.
2. Run `./gradlew build` and inspect JaCoCo output for missed new branches, adding tests for meaningful omissions rather than targeting a numeric threshold.
3. Build the production container.
4. Run local full-page and widget smoke checks.

Before the first production deployment:

1. Generate and securely store `PIN_PEPPER`.
2. Configure `PIN_BACKFILL_EXPORT_PATH` to a file inside a new, dedicated `/tmp` directory that the runner can create with `0700`.
3. Prepare to provision `BITBRUSH_E2E_UUID` after deployment with the rotated private UUID for a dedicated smoke-test identity; the currently exposed UUID cannot be reused.
4. Confirm there is a current PostgreSQL backup.
5. Deploy with Fly's `immediate` strategy, accepting a brief maintenance window so no old application instance can insert a row after the new non-null schema is applied.
6. Monitor Flyway, the post-commit export runner, and health-check output.
7. Retrieve the backfill export from the machine named in the runner log. If that machine disappears first, restart with the same pepper and export path to reproduce it.
8. Verify selected exported PINs against recovery without exposing them in shell history, and capture the recovered private UUID for the dedicated smoke-test identity.
9. Store that private UUID as `BITBRUSH_E2E_UUID` through the existing CI/operator secret mechanism.
10. Unset `PIN_BACKFILL_EXPORT_PATH` before deleting the remote file, so a later restart cannot recreate plaintext. Apply the configuration restart, then remove or confirm loss of the dedicated export directory and all its contents on any surviving machine.
11. Exercise create, reconnect, recover, incorrect-PIN, hash-capacity, multi-session, and throttle behavior.
12. Run the production widget smoke suite with the provisioned UUID.

The immediate strategy eliminates old/new write overlap but does not make an old application binary schema-compatible. Rollback after the non-null migration remains a roll-forward database operation: the old application cannot create users without `pin_hash`. The deployment notes must therefore include a tested corrective release/database procedure rather than assuming an application-only rollback is safe.

## Security references

- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) for Argon2id, unique salts, work factors, and pepper separation.
- [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html) for rate limiting of low-entropy authentication secrets.
- [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html) for Argon2 input and parameter definitions.
- [Fly.io request-header documentation](https://fly.io/docs/networking/request-headers/) for production client-IP resolution.
- [Spring Framework STOMP token authentication](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/authentication-token-based.html) for authenticating CONNECT in `ChannelInterceptor.preSend` and associating the resulting Principal with the session.
- [Spring Framework STOMP application events](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/application-context-events.html) for CONNECTED/disconnect semantics and idempotent disconnect handling.

## Documentation changes

- Update `AGENTS.md`/project architecture guidance with PIN recovery endpoints, storage behavior, throttling, and the new environment variables.
- Update deployment documentation with pepper generation/backup, migration export retrieval/deletion, and rollback constraints.
- Update client documentation to state that UUID and username—but never PIN—are stored locally.
- Document the single-instance assumption for recovery throttling.
