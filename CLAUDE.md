# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is BitBrush

A collaborative pixel art canvas (like Reddit r/Place) built with Java 21 + Spring Boot 3.5. Users place colored pixels on a shared 250x250 grid in real-time via WebSocket/STOMP. Placement is rate-limited by a banking system that earns points over time. The full-page client is a single vanilla HTML/JS/CSS file (`src/main/resources/static/index.html`); the independently embeddable client is `bitbrush-widget.js`. Neither client has an application build step.

BitBrush is a production Spring Boot application. When making changes, follow existing Spring idioms (constructor injection, records for DTOs/config, RFC 7807 ProblemDetail for errors, JPA entities with repository + service layering).

## Build & Run Commands

```bash
./gradlew bootRun          # Start dev server at http://localhost:8080 (dev profile, file-based H2)
./gradlew test             # Run all tests (unit, slice, integration, WebSocket)
./gradlew test --tests "*ClassName"        # Run a specific test class
./gradlew test --tests "*ClassName.methodName"  # Run a specific test method
./gradlew clean build     # Full application verification
./gradlew migrationTest   # PostgreSQL/Testcontainers legacy migration and constraint races
docker compose up --build  # Container with PostgreSQL (docker profile)

# Deterministic local full-page/widget browser tests
cd e2e
npm ci
npx playwright install chromium
npm run test:local
# Explicit production operation only; requires provisioned BITBRUSH_E2E_UUID
npm run test:production
```

The dev profile (`application-dev.properties`) uses `ddl-auto=create` — schema is dropped and recreated on each startup. The H2 console is available at `/h2-console` in dev. `./gradlew test` also generates JaCoCo HTML and CSV reports under `build/reports/jacoco/test/`; audit meaningful secret/concurrency/authorization/filesystem branches, not accessor percentages. Local and production Playwright configurations are deliberately disjoint. CI gates the build, PostgreSQL migration tests, local browser suite and container build.

## Architecture

**Request flow:** REST Controller → Service → Repository (JPA). Dev/test use H2; docker/prod use PostgreSQL. Real-time broadcasts go through `SimpMessagingTemplate` → STOMP broker → subscribed clients.

**Key layers:**
- `controller/` — REST endpoints under `/api` (`CanvasController`, `PixelController`, `UserController`, `StatsController`)
- `websocket/` — STOMP controllers (`BankController`, `UserCountController`) and `WebSocketEventListener` for session tracking
- `service/` — `UserIdentityService` owns create/reconnect/recover; `PinCredentialService`/`PinCredentialCodec` own canonicalization, HMAC/Argon2 and global capacity; `RecoveryAttemptService` owns bounded process-local rolling limits; `ClientIpResolver` validates trusted proxy metadata. `PixelService` owns canvas state/placement/stats; `BankingService` owns banking; `CanvasExportService` exports PNG; `TurnstileService` verifies Cloudflare challenges and retains admission state. `LegacyPinExportRunner` and `SecureExportPublisher` reproduce/publish protected legacy credentials after Flyway commits.
- `repository/` — Spring Data JPA interfaces with custom JPQL queries for last-writer-wins canvas state
- `model/` — JPA entities: `Pixel` (append-only placement log using public `authorId`), `User` (private UUID, exact-case username, public author ID, salted PIN hash, backfilled flag)
- `dto/` — Immutable Java records for request/response payloads
- `config/` — `BitbrushProperties` (type-safe config record), `WebSocketConfig` (STOMP broker + UUID-based Principal), `PaletteConfig` (216-color web-safe RGB palette (6x6x6 color cube)), `CorsConfig` (allowed origins for GitHub Pages/Fly.io/custom domain), `TurnstileProperties` (Cloudflare Turnstile keys), `StartupLogger`
- `exception/` — `GlobalExceptionHandler` returns RFC 7807 ProblemDetail responses; custom exceptions include `InsufficientBalanceException`, `TurnstileException`, `UserNotFoundException`

**Real-time architecture:**
- WebSocket endpoint at `/ws` (SockJS-enabled)
- STOMP destinations: `/topic/pixels` (broadcasts), `/topic/users/count` (active session count), `/user/queue/bank` (ongoing per-user balance updates), plus `/app/users/count` and `/app/bank` subscriptions for direct initial state
- Client identity: registered canonical private UUID passed in exactly one STOMP CONNECT/STOMP header and authenticated synchronously by `StompAuthenticationInterceptor` before broker/application processing. The Principal routes by private UUID but renders a constant in diagnostics; public author IDs cannot authenticate.
- The online count measures STOMP sessions, not unique users; multiple tabs count separately

**Banking system (in-memory, no DB):**
- `bankMap` uses atomic `compute()`/`computeIfAbsent()` for balance changes; `SimpUserRegistry` supplies connected principals across sessions
- A global `@Scheduled` fixed-delay tick grants one point to each connected UUID every `earnRateSeconds`, up to `maxBanked`
- Balances stop earning while disconnected but remain spendable and survive reconnects; they reset on server restart
- Placement batches cost one point per coordinate and may persist only the affordable prefix while still returning 201; requests are limited to 50 coordinates
- Multiple tabs sharing a private UUID earn once per tick and receive the same bank updates
- Insufficient balance returns **402 Payment Required** (not 429) via `InsufficientBalanceException` → `GlobalExceptionHandler`

**Identity and bot protection:**
- POST `/api/users` requires UUID, username, PIN and confirmation plus `X-Turnstile-Token`; canonicalize/confirm before challenge and persist a complete row in a short committed transaction before marking verified. There is no PIN-less compatibility branch.
- POST `/api/users/reconnect` authenticates the stored private UUID; POST `/api/users/recover` authenticates exact-case username/PIN with a new Turnstile challenge and returns the same private UUID. All successful identity DTOs use `Cache-Control: no-store` and contain only authoritative UUID/username.
- Recovery orders IP accounting → challenge → account reservation → real/dummy hash verification. Limits are five/account and twenty/IP per rolling 15 minutes, with 10,000-key bounds; errors do not disclose account existence. A 503 hash-capacity failure cancels only its account reservation. All throttling is process-local: multiple application processes require shared state.
- Production accepts one numeric `Fly-Client-IP`; dev/test/docker use the remote socket address. The identity request filter caps exactly the three POST endpoints at 4,096 bytes before parsing.
- `/api/pixels` accepts `X-Turnstile-Token` as a one-request fallback for an unverified UUID; failure returns 403
- Verification survives disconnect/reconnect for the process lifetime. Authenticated STOMP reconnect restores it after restart. The server secret comes from `TURNSTILE_SECRET_KEY`; the full-page site key is embedded in `index.html`, while widgets receive their public key through `window.bitbrushConfig`.
- PINs are opaque transient client input: NFC, control/format/separator replacement with ordinary spaces, exactly four Unicode code points, case/space significant. Both clients store only UUID/username as identity data; never PINs. Public responses/broadcasts use `authorId`, never a private bearer.

**Credential/operator configuration:**
- Docker/prod require `PIN_PEPPER`: Base64 encoding of at least 32 random bytes, generated/backed up in a secrets manager separately from PostgreSQL. Never commit a real pepper. Losing/changing it breaks existing PIN verification and legacy reproduction; no transparent rotation/reset exists. Dev/test fixture peppers are explicitly non-production.
- Production Argon2id uses `19456/2/1/32` (memory KiB/iterations/parallelism/output bytes), 16-byte random salts and at most two concurrent real/dummy operations. Keep global minima; calibration is opt-in with `PIN_CALIBRATION=true`. Run the exact 512 MiB Docker command in README from the checkout root and record its median/max timings in that document; timing-only output is `build/reports/pin-calibration.txt`.
- Local 2026-09-13 calibration passed with two overlapping hashes: hash median/max 79.485/119.037 ms; verification 76.741/100.476 ms; concurrent hash 255.735/258.211 ms. The exact Docker command used swap during cold Gradle configuration, so it is not proof of swap-free/full-application fit. See README for the memory measurement and runner-budget tradeoff.
- Keep all three Spring STOMP logger categories at INFO or higher: `org.springframework.messaging.simp`, `org.springframework.web.socket.messaging`, `org.springframework.web.SimpLogging`. Keep the narrow `org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF` boundary: even ERROR can disclose database operands. No credential-bearing body/header/bind logs, exception chains, console output, metrics or traces; tests must not print secret assertion operands or request dumps on failure.
- `PIN_BACKFILL_EXPORT_PATH` optionally enables the post-commit legacy export. Use an app-owned dedicated 0700 `/tmp` directory and a 0600 regular, non-symlink target. Retrieve from the logged Fly machine via protected SSH/SFTP, then unset the variable/apply the restart before deleting remote plaintext. It is reproducible with the same pepper/public author IDs; new-user PINs are never exported.
- Follow README's PostgreSQL backup/restore rehearsal and delivery procedure. Fly `[deploy] strategy = "immediate"` is mandatory to prevent old/new writers overlapping V4. After V4, corrections are roll-forward releases/migrations tested against a restored V4 copy; an old binary cannot create users. Provision `BITBRUSH_E2E_UUID` using the rotated UUID of a dedicated smoke identity after recovery.

**CORS:**
- REST CORS applies to `/api/**`; SockJS/WebSocket origins are configured separately with the same patterns
- Allowed origins are HTTPS `*.github.io`, HTTPS `*.fly.dev`, `https://mark.dingwall.com.au`, and HTTP localhost on any port. REST credentials are disabled and allowed headers include `X-Turnstile-Token`

**Embeddable widget:**
- `bitbrush-widget.js` — standalone JS file that injects a full BitBrush canvas into any page
- Configured via `window.bitbrushConfig` (`server`, `container`, `turnstileSiteKey`)
- Supports wheel/pinch zoom, touch and right-drag panning, a high-zoom grid, and a loading overlay
- Persists only the anonymous UUID and username in localStorage; viewport, palette, and bank UI state are session-only
- Designed for cross-origin embedding (e.g., GitHub Pages pointing at Fly.io backend)

**Eraser convention:** `paletteIndex == 0` is the eraser. Current-canvas, author, and statistics queries filter `WHERE paletteIndex <> 0`; pixel-info retrieves the latest row first and then treats index 0 as empty.

**Database migrations:**
- Flyway manages schema for docker and prod profiles (`src/main/resources/db/migration/`)
- Dev and test profiles use Hibernate `ddl-auto` with `spring.flyway.enabled=false`

## Spring Profiles

| Profile | DB | Schema mgmt | H2 Console | Use case |
|---------|-----|-------------|------------|----------|
| `dev` | H2 file: `./data/bitbrush-dev` | `ddl-auto=create` | Yes `/h2-console` | Local development (`bootRun` default) |
| `test` | H2 in-memory | `ddl-auto=create-drop` | No | Test suite |
| `docker` | PostgreSQL (via docker-compose) | Flyway + `ddl-auto=validate` | No | Container deployment |
| `prod` | PostgreSQL (via JDBC `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`) | Flyway + `ddl-auto=validate` | No | Fly.io production |

## Testing Patterns

- **Unit tests**: `@Mock` + `@InjectMocks` with Mockito (e.g., `BankingServiceTest`, `PixelServiceTest`, `CanvasExportServiceTest`)
- **Controller slice tests**: `@WebMvcTest` with `@MockitoBean` for isolated HTTP testing (e.g., `*SliceTest.java`)
- **Integration tests**: `@SpringBootTest` for full context; read-oriented Canvas/Stats tests are transactional, while Pixel/User/error-handler tests deliberately are not
- **WebSocket tests**: `WebSocketIntegrationTest` uses `StompSession` + `CompletableFuture` against a live server
- **Repository tests**: `@DataJpaTest` with auto-rollback (e.g., `PixelRepositoryTest`, `UserRepositoryTest`)
- **Local browser tests**: deterministic Playwright HTTP/Turnstile/STOMP harness covers full-page and widget identity UI; `npm run test:local` does not navigate to production
- **Production smoke tests**: separately configured Playwright suite exercises the deployed widget/backend using provisioned `BITBRUSH_E2E_UUID`; run only as an explicit deployment operation
- **Fixtures/privacy**: creation-contract tests supply PIN/confirmation; unrelated tests persist complete users through `UserTestFixtures` or a complete specialized fixture. Use constant assertion diagnostics for UUIDs/PINs/hashes and disable MockMvc failure dumps for credential-bearing requests.

Tests use the `test` profile (in-memory H2, `create-drop`).

## Key Design Decisions

- **Append-only pixel log**: `Pixel` table stores every placement; "current state" is derived via MAX(placedAt) subqueries. No UPDATE/DELETE on pixels.
- **Anonymous bearer identity with PIN recovery**: routine use authenticates a private UUID; username/PIN restores that UUID. Username "You" is reserved. Public author IDs are independent and never authenticate.
- **Static clients without an app build**: The full-page client lives in `index.html`; the widget is a separate dependency-free JavaScript client. Node/npm is used only for Playwright E2E tooling.
- **Config as records**: `BitbrushProperties` is an immutable `@ConfigurationProperties` record with `@Validated` constraints.
- **Flyway migrations**: Docker and prod profiles use Flyway for schema management (`db/migration/`). Dev and test use Hibernate auto-DDL.
