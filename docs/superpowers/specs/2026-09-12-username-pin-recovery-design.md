# Username PIN Recovery Design

## Summary

BitBrush will require each new user to choose a case-sensitive, four-character PIN when creating a username. If the browser's BitBrush identity is later removed from `localStorage`, the user can recover the original UUID by submitting the username and PIN. Routine reconnects continue to use the locally stored UUID and do not store or resend the PIN.

The PIN is a lightweight recovery credential for this anonymous canvas, not a replacement for the application's UUID-based identity model. The implementation will nevertheless protect it with canonical Unicode handling, a secret pepper, per-user salts, Argon2id, Turnstile, throttling, generic failures, and careful secret handling.

## Goals

- Let a user recover the original UUID associated with a username after losing local browser state.
- Preserve pixel authorship and any live in-memory banking state by restoring the original UUID.
- Avoid storing plaintext PINs on either the server or client.
- Support case-sensitive Unicode PINs consistently across Java and both browser clients.
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

Both clients store a generated UUID and username in `localStorage`. At startup they silently submit both values to `POST /api/users`. The endpoint treats an unknown UUID as registration and an existing UUID as reconnection. Existing UUIDs bypass a new Turnstile check and are re-added to the in-memory verified set. The UUID is also used as the STOMP principal and pixel author identifier.

There is no PIN, recovery operation, identity lookup, cookie, durable session, or bearer token other than possession of the UUID. Losing local storage therefore loses access to the original identity.

## User experience

The identity modal has two explicit modes, following a conventional create/login pattern.

### Create account

The default view contains:

- Username.
- PIN.
- Confirm PIN.
- A primary **Create account** button.
- A smaller **Already have one? Log in** action.

The two PIN fields must match after canonicalization. A successful response stores only the returned/confirmed UUID and authoritative username in the client's existing local-storage keys. The PIN fields are cleared as the modal closes.

### Log in / recover

The recovery view contains:

- Username.
- PIN.
- A primary **Log in** button.
- A smaller **Need an account? Create one** action.

Successful recovery stores the original UUID and authoritative username, closes the modal, and proceeds with the existing WebSocket/client initialization. Failures leave the form open and clear the PIN.

PIN fields use password masking and appropriate `autocomplete` values. They do not use HTML `maxlength=4`, because HTML counts UTF-16 code units and would reject some valid four-code-point PINs. Client-side validation uses Unicode code points for fast feedback, but the server is authoritative.

### Routine reconnect

Whenever a UUID is present, even if the username entry is missing, the client sends the UUID to the reconnect endpoint. The authoritative username in the response replaces the locally stored username. The PIN is neither needed nor available. If the UUID is unknown, such as after a development database reset, the client removes the stale local identity and opens the Create/Log-in modal. A username without a UUID is also discarded as stale.

The client no longer creates and persists a UUID merely because the page loaded. It generates a UUID only when the user submits Create account; recovery supplies the existing UUID. This avoids leaving an unrelated UUID in storage while the user is trying to recover an identity.

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
  "pin": "A😀b!"
}
```

The endpoint always represents creation. It validates the request, requires Turnstile, rejects an existing UUID or username, hashes the canonical PIN, inserts the user, and marks the UUID verified. Success returns `201 Created` with no PIN in the response.

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

Rate-limited recovery returns `429 Too Many Requests` with `Retry-After`. Turnstile failure remains `403 Forbidden`. The response and logs never contain the submitted PIN.

## Component boundaries

### `UserController`

The controller exposes the three identity operations, obtains the Turnstile token and request metadata, and maps successful results to HTTP responses. It delegates identity rules to `UserIdentityService`, bot verification to the existing `TurnstileService`, and source-IP resolution/throttling to focused collaborators.

### `UserIdentityService`

This service owns creation, UUID reconnection, username recovery, repository coordination, and verification-cache updates. User-registration behavior moves out of `PixelService`, returning that class to canvas and pixel responsibilities.

The service never accepts or returns a raw PIN beyond the duration of a create or recover call. It never logs one.

### `PinCredentialService`

This service is the sole application implementation of PIN canonicalization, hashing, and verification. The migration reuses the same lower-level credential codec so rollout hashes cannot diverge from runtime hashes.

### `RecoveryAttemptService`

This service owns bounded, expiring, in-memory counters. It uses an injected `Clock` so expiry is deterministic in tests. Updates are atomic so simultaneous recovery attempts cannot exceed a limit through races.

### `ClientIpResolver`

In production, the resolver obtains the public caller from Fly's `Fly-Client-IP` header. In profiles not hosted behind Fly Proxy it uses the servlet request's socket address. It validates and canonicalizes IPv4/IPv6 values rather than trusting arbitrary strings.

### Existing services

`TurnstileService` remains the only Cloudflare integration. Creation and recovery call `verify(token)`, perform the database/credential operation, and call `markVerified(uuid)` only after that operation succeeds. This ordering prevents a failed database write or failed credential check from leaving a UUID verified. Reconnect calls `markVerified(uuid)` only after repository lookup succeeds.

The existing `verifyAndRemember` behavior is therefore decomposed into its existing `verify` and `markVerified` operations where transaction ordering matters rather than duplicated in a new Turnstile implementation.

The two static clients reuse their existing Turnstile rendering, wait, reset, and header helpers. Their small identity UI implementations remain mirrored because `bitbrush-widget.js` must stay independently embeddable.

## PIN canonicalization

The same conceptual algorithm is implemented by the server and both clients:

1. Apply Unicode NFC normalization.
2. Replace every code point in Unicode general categories `Cc` (control), `Cf` (format), `Zs` (space separator), `Zl` (line separator), and `Zp` (paragraph separator) with U+0020 SPACE.
3. Reject unpaired UTF-16 surrogates; they are not valid Unicode scalar values and cannot represent valid UTF-8 input.
4. Require exactly four Unicode code points after replacement.
5. Preserve case and all remaining code points exactly.
6. Encode the result as UTF-8 for credential processing.

Spaces are significant and are not trimmed. Canonicalization occurs before comparing the Create and Confirm PIN fields. The server limits the raw request size before doing expensive work, preventing an oversized value from being used as a resource-exhaustion input.

## Credential storage

The `users` table gains a `pin_hash` column. Its final schema is `NOT NULL`; plaintext PIN and salt columns are not added.

For each credential:

1. Canonicalize the PIN.
2. Compute HMAC-SHA-256 over the canonical UTF-8 bytes with a server-wide secret pepper.
3. Encode the HMAC output in an unambiguous fixed representation.
4. Feed that value to Argon2id with a new cryptographically random per-user salt.
5. Store the standard encoded Argon2id string containing algorithm version, work parameters, salt, and hash.

The application uses a maintained Java implementation rather than implementing Argon2 itself. Production parameters meet or exceed current OWASP guidance and are calibrated against the 512 MiB Fly machine before release. Tests may inject cheaper parameters where they are testing orchestration rather than the production work factor.

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

Flyway adds `pin_hash`, initially nullable only within the migration sequence. A Java migration then:

1. Detects legacy rows without a hash.
2. Requires `PIN_BACKFILL_EXPORT_PATH` when such rows exist.
3. Creates a new export file without overwriting an existing target and restricts it to owner read/write (`0600`).
4. Generates an independent random four-digit PIN for each legacy user with `SecureRandom`.
5. Hashes each PIN through the same peppered Argon2id pipeline used by the application.
6. Updates the corresponding row and writes `username<TAB>PIN` to a temporary export.
7. Applies the `NOT NULL` constraint after every legacy row is populated.
8. Flushes the export and moves the temporary export to the configured final path only after all migration statements succeed.

The migration logs only the export path, row count, and Fly machine identifier when available. It never logs a PIN. Exceptions remove the partial temporary export where possible and fail the migration so Flyway/database transaction handling can roll back. The final export is not overwritten on retry, preventing silent loss or replacement of issued values.

A fresh database with no legacy rows does not require an export path, but still receives the final non-null schema. Backfilled PINs are functional if distributed from the export; otherwise they remain unknown to their users.

On Fly.io, Flyway runs during normal application startup on the machine that acquires the migration lock. The configured path should be under `/tmp`, for example `/tmp/bitbrush-pin-backfill.tsv`. The operator retrieves it from SSH, stores or distributes it appropriately, and deletes it promptly. Fly's ephemeral filesystem is not treated as a durable backup.

## Error handling and privacy

New domain exceptions are handled centrally as RFC 7807 problems, following existing project conventions:

- Duplicate username/UUID: `409 Conflict`.
- Invalid credentials: `401 Unauthorized` with one generic response.
- Recovery throttled: `429 Too Many Requests` plus `Retry-After`.
- Turnstile rejected: existing `403 Forbidden` response.
- Unknown reconnect UUID: existing `404 User Not Found` response.
- Invalid request or canonical PIN: `400 Bad Request`.

Recovery responses use `Cache-Control: no-store`. Controller and service logging includes neither PINs nor hashes. Username logging is limited to what is operationally necessary, and failures do not distinguish account existence. The temporary migration export is the only plaintext-at-rest exception, is explicitly requested, has restrictive permissions, and is operator-deleted after retrieval.

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

### Throttling unit and concurrency tests

With an injected fake clock, tests cover both limits immediately below, at, and after their boundaries; rolling-window expiry; successful account reset; unknown-account accounting; IP canonicalization; map-capacity fail-closed behavior; and independence between usernames/IPs.

Concurrent tests release many workers through a barrier and prove that atomic updates never permit more successful checks than configured. Cleanup is exercised concurrently with checks to catch unsafe iteration or lost updates.

### Service tests

`UserIdentityService` tests cover:

- Successful creation persists only a hash and marks the UUID verified.
- Duplicate UUID and duplicate username behavior.
- Repository failure does not mark a UUID verified.
- Reconnect returns the authoritative username and never mutates it.
- Unknown reconnect UUID.
- Recovery returns the original UUID and resets only the appropriate account counter.
- Incorrect PIN, unknown username, and dummy-hash execution.
- Turnstile/credential failure never marks a UUID verified.
- Existing pixel authorship remains attached to the recovered UUID.
- Concurrent creation retains database uniqueness guarantees.

### Controller slice tests

MockMvc slice tests cover all three contracts, missing/malformed bodies, Unicode JSON, Turnstile headers and call ordering, every expected status, generic problem details, `Retry-After`, `Cache-Control: no-store`, and absence of PIN/hash response fields.

### Repository and application integration tests

Repository tests verify hash persistence, exact username lookup, uniqueness, and non-null enforcement. Full Spring tests execute create → reconnect and create → recover → place-pixel request sequences, proving the recovered UUID works through the existing placement and verification path.

All existing tests that create users are updated deliberately: creation tests provide PINs; tests concerned only with registered users use service fixtures or the appropriate identity operation. This prevents permissive compatibility shortcuts from hiding missing credentials.

### Migration tests

A PostgreSQL Testcontainers test starts from the V1 schema, inserts representative legacy users, and runs the real Flyway migration. It verifies:

- Every legacy row has a non-null, distinct, valid Argon2id hash.
- Each exported PIN verifies for its matching row with the configured pepper.
- Generated legacy PINs are exactly four decimal digits.
- The TSV escapes/represents every currently valid username safely.
- The export has `0600` permissions on supported test hosts.
- PINs and pepper do not appear in Flyway/application logs captured by the test.
- An existing export is not overwritten.
- An unwritable/missing required export path fails migration.
- Failure removes a partial temporary file and leaves V1 data recoverable through transaction rollback.
- An empty V1 database migrates without an export path.
- Re-running Flyway is idempotent and does not regenerate credentials.
- The resulting schema passes Hibernate `validate` under the production database dialect.

The container test is marked to skip only when Docker is genuinely unavailable locally. CI includes an explicit migration-test task and fails if that task is skipped, ensuring GitHub Actions executes the PostgreSQL path. Unit tests still exercise migration/backfill collaborators without Docker.

### Client verification

Because the static clients intentionally have no application build step, verification includes:

- JavaScript syntax checks.
- One shared set of PIN canonicalization test vectors consumed by the Java tests and both client suites, preventing drift in Unicode behavior.
- A separate local Playwright configuration that serves the static resources, substitutes deterministic Turnstile/API boundaries, and tests the full-page client and standalone widget before deployment.
- Automated browser coverage for create/recover mode switching, confirmation comparison after canonicalization, every shared Unicode vector, success/failure rendering, stale UUID handling, and the exact local-storage keys and values retained after success.
- Local browser smoke tests for create, mode switching, confirmation mismatch, Unicode PIN feedback, recovery, stale UUID handling, storage contents, Turnstile refresh, and both full-page and widget clients.
- Updates to the production Playwright smoke suite only where they can run safely against the deployed service without embedding reusable PINs in source control.

No automated test writes real PINs, hashes, or pepper values to test output.

## Verification and rollout

Before merge:

1. Run the focused credential, identity, controller, repository, throttling, and migration tests.
2. Run `./gradlew build` and inspect JaCoCo output for missed new branches, adding tests for meaningful omissions rather than targeting a numeric threshold.
3. Build the production container.
4. Run local full-page and widget smoke checks.

Before the first production deployment:

1. Generate and securely store `PIN_PEPPER`.
2. Configure `PIN_BACKFILL_EXPORT_PATH` to a new `/tmp` path.
3. Confirm there is a current PostgreSQL backup.
4. Deploy and monitor Flyway plus health-check output.
5. Retrieve the backfill export from the machine named in the migration log.
6. Verify selected exported PINs against recovery without exposing them in shell history.
7. Delete the remote temporary export.
8. Exercise create, reconnect, recover, incorrect-PIN, and throttle behavior.
9. Run the production widget smoke suite.

Rollback after the non-null migration is a roll-forward database operation: the old application cannot create users without `pin_hash`. The deployment notes must therefore include a tested corrective release/database procedure rather than assuming an application-only rollback is safe.

## Security references

- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) for Argon2id, unique salts, work factors, and pepper separation.
- [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html) for rate limiting of low-entropy authentication secrets.
- [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html) for Argon2 input and parameter definitions.
- [Fly.io request-header documentation](https://fly.io/docs/networking/request-headers/) for production client-IP resolution.

## Documentation changes

- Update `AGENTS.md`/project architecture guidance with PIN recovery endpoints, storage behavior, throttling, and the new environment variables.
- Update deployment documentation with pepper generation/backup, migration export retrieval/deletion, and rollback constraints.
- Update client documentation to state that UUID and username—but never PIN—are stored locally.
- Document the single-instance assumption for recovery throttling.
