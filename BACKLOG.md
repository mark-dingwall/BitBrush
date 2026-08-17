# BitBrush Backlog

This backlog captures known product and reliability work. Items are ordered by priority, but none is currently scheduled.

## P0 — Recover an account with username and PIN

Users currently depend on a UUID stored in one browser. Losing that local state permanently disconnects them from their existing identity.

- [ ] Let the registration form accept both a username and a PIN.
- [ ] When the username is new, create the account, store only a salted, memory-hard password hash of the PIN, and return the server-owned account UUID for local storage.
- [ ] When the username already exists, verify the PIN and return the same UUID so the account can be reused in a fresh browser.
- [ ] Do not treat a client-supplied UUID as proof of account ownership; require the PIN for every path that restores an existing identity.
- [ ] Make usernames case-insensitively unique and define a safe migration path for any existing duplicates.
- [ ] Keep Turnstile in the flow and add rate limiting plus non-enumerating error responses for failed recovery attempts.
- [ ] Never log, return, or persist a plaintext PIN.
- [ ] Support the same create/recover flow in the full-page client and embedded widget, with tests for new accounts, successful recovery, wrong PINs, duplicate names, and browser-state loss.

## P0 — Re-establish verification after WebSocket reconnect

A disconnect removes the UUID from the in-memory Turnstile verification cache, but reconnecting clients do not re-register before placing pixels. Placements can therefore start returning `403 Forbidden` after a transient connection loss.

- [ ] Make both clients restore their verified session after every successful WebSocket reconnect.
- [ ] Prevent placement attempts until identity and verification restoration has completed, with a clear reconnecting state in the UI.
- [ ] Cover disconnect, automatic reconnect, and the first post-reconnect placement with integration or browser tests.

## P0 — Support multiple live sessions for one account

Connection and banking state assumes one active WebSocket session per UUID. If the same account is open in multiple tabs or clients, one disconnect can mark the user offline while another session is still active.

- [ ] Track the set or reference count of active session IDs per UUID.
- [ ] Start earning on the first connection and stop only after the final session disconnects.
- [ ] Keep verification and bank updates valid while any session remains connected.
- [ ] Make duplicate connect and disconnect events idempotent, and test multi-tab connection orderings.

## P1 — Reconcile partially accepted drag batches

The server can accept only the affordable prefix of a drag batch while returning the same success status as a fully accepted batch. Because clients paint the whole drag optimistically, unaffordable pixels can remain visible locally until a later refresh.

- [ ] Return an explicit result that identifies how many coordinates were accepted, or return the accepted coordinates themselves.
- [ ] Have both clients commit accepted pixels and roll back or reload rejected optimistic pixels.
- [ ] Show useful feedback when a batch is truncated by the placement balance.
- [ ] Test zero-balance, partial-balance, full-balance, and concurrent balance-update cases.

## P1 — Rebuild canvas state cleanly after reconnect

The full-page client reloads canvas pixels after reconnect without first clearing its image buffer. A pixel erased while the client was offline can therefore remain visible locally.

- [ ] Treat a reconnect snapshot as authoritative and clear the current buffer before applying it.
- [ ] Avoid visible flicker or partially rendered snapshots while rebuilding.
- [ ] Test that offline paints and erasures both converge to server state after reconnect.

## P2 — Make canvas dimensions configurable end to end

Backend configuration exposes canvas dimensions, but request validation and both clients still assume a 250×250 canvas.

- [ ] Expose authoritative canvas metadata to clients or generate their configuration from one source.
- [ ] Use the configured dimensions for validation, rendering, coordinate conversion, export, grid drawing, and interaction bounds.
- [ ] Preserve 250×250 as the default.
- [ ] Test at least one non-square, non-default canvas to catch width/height assumptions.
