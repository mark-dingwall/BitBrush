# BitBrush Backlog

This backlog captures known product and reliability work. Items are ordered by priority, but none is currently scheduled.

## Completed

- Both clients support Turnstile-protected account creation and recovery with an exact-case username and PIN. Recovery restores the same private UUID.
- Verification survives disconnects while the server is running. Authenticated STOMP reconnect restores it after a server restart.
- Multiple tabs with one identity earn points once per tick and receive the same balance updates.
- Both clients clear the canvas buffer before painting a reconnect snapshot, including pixels erased while they were offline.

## P1 — Reconcile partially accepted drag batches

The server can accept only the affordable prefix of a drag batch while returning the same success status as a fully accepted batch. Both clients reload the canvas after a successful multi-pixel batch to replace their optimistic paint with the accepted result.

- [ ] Return an explicit result that identifies how many coordinates were accepted, or return the accepted coordinates themselves.
- [ ] Reconcile optimistic pixels in both clients using the explicit placement result.
- [ ] Show useful feedback when a batch is truncated by the placement balance.
- [ ] Test zero-balance, partial-balance, full-balance, and concurrent balance-update cases.

## P1 — Reconcile optimistic placement consistently

- [ ] Share placement submission and rejection handling between keyboard and drag.
- [ ] Reconcile the displayed bank balance with authoritative state after a rejected placement.

## P2 — Make canvas dimensions configurable end to end

Backend configuration exposes canvas dimensions, but request validation and both clients still assume a 250×250 canvas.

- [ ] Expose authoritative canvas metadata to clients or generate their configuration from one source.
- [ ] Use the configured dimensions for validation, rendering, coordinate conversion, export, grid drawing, and interaction bounds.
- [ ] Preserve 250×250 as the default.
- [ ] Test at least one non-square, non-default canvas to catch width/height assumptions.

## P2 — Order live updates around authoritative canvas snapshots

A WebSocket pixel delta can arrive while `/api/canvas` is in flight and then be
overwritten when the older snapshot is applied. In the full-page client, an
in-flight pixel-info response can similarly repopulate metadata caches after a
snapshot invalidates them.

- [ ] Queue live pixel deltas while a snapshot is loading, then replay them in arrival order after applying the snapshot.
- [ ] Prevent metadata responses started before a snapshot from repopulating the refreshed caches.
- [ ] Cover both clients with deterministic ordering tests.

## P2 — Serve the full-page Turnstile site key from configuration

- [ ] Let the full-page client use the configured public key at runtime for both local development and deployment.
- [ ] Configure the matching public key explicitly in the packaged browser fixture in place of the HTML substitution.
