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
docker compose up --build  # Container with PostgreSQL (docker profile)

# Live production widget smoke tests (requires Node.js, network, and Chromium)
cd e2e
npm ci
npx playwright install chromium
npx playwright test
```

The dev profile (`application-dev.properties`) uses `ddl-auto=create` — schema is dropped and recreated on each startup. The H2 console is available at `/h2-console` in dev. `./gradlew test` also generates JaCoCo HTML and CSV reports under `build/reports/jacoco/test/`; there is no enforced coverage threshold. The Playwright suite is fixed to the deployed production site and backend, not a local E2E environment.

## Architecture

**Request flow:** REST Controller → Service → Repository (JPA). Dev/test use H2; docker/prod use PostgreSQL. Real-time broadcasts go through `SimpMessagingTemplate` → STOMP broker → subscribed clients.

**Key layers:**
- `controller/` — REST endpoints under `/api` (`CanvasController`, `PixelController`, `UserController`, `StatsController`)
- `websocket/` — STOMP controllers (`BankController`, `UserCountController`) and `WebSocketEventListener` for session tracking
- `service/` — `PixelService` (canvas state, pixel placement, user registration, stats), `BankingService` (placement point banking), `CanvasExportService` (PNG export), `TurnstileService` (Cloudflare bot verification via RestClient)
- `repository/` — Spring Data JPA interfaces with custom JPQL queries for last-writer-wins canvas state
- `model/` — JPA entities: `Pixel` (append-only placement log), `User` (UUID-to-username mapping)
- `dto/` — Immutable Java records for request/response payloads
- `config/` — `BitbrushProperties` (type-safe config record), `WebSocketConfig` (STOMP broker + UUID-based Principal), `PaletteConfig` (216-color web-safe RGB palette (6x6x6 color cube)), `CorsConfig` (allowed origins for GitHub Pages/Fly.io/custom domain), `TurnstileProperties` (Cloudflare Turnstile keys), `StartupLogger`
- `exception/` — `GlobalExceptionHandler` returns RFC 7807 ProblemDetail responses; custom exceptions include `InsufficientBalanceException`, `TurnstileException`, `UserNotFoundException`

**Real-time architecture:**
- WebSocket endpoint at `/ws` (SockJS-enabled)
- STOMP destinations: `/topic/pixels` (broadcasts), `/topic/users/count` (active session count), `/user/queue/bank` (ongoing per-user balance updates), plus `/app/users/count` and `/app/bank` subscriptions for direct initial state
- Client identity: UUID passed as STOMP CONNECT header, assigned as `Principal` by `WebSocketConfig`'s channel interceptor — required for `SimpUserRegistry` to work
- The online count measures STOMP sessions, not unique users; multiple tabs count separately

**Banking system (in-memory, no DB):**
- `bankMap` uses atomic `compute()`/`computeIfAbsent()` for balance changes; separate concurrent maps track connected sessions
- A global `@Scheduled` fixed-delay tick grants one point to each connected UUID every `earnRateSeconds`, up to `maxBanked`
- Balances stop earning while disconnected but remain spendable and survive reconnects; they reset on server restart
- Placement batches cost one point per coordinate and may persist only the affordable prefix while still returning 201; requests are limited to 50 coordinates
- Connection tracking currently assumes one active STOMP session per UUID
- Insufficient balance returns **402 Payment Required** (not 429) via `InsufficientBalanceException` → `GlobalExceptionHandler`

**Bot protection:**
- New-user registration verifies `X-Turnstile-Token` through Cloudflare's siteverify endpoint and remembers the UUID in an in-memory cache
- Re-registering an existing UUID adds it back to the verification cache without another challenge; verified UUIDs place without per-placement tokens
- `/api/pixels` accepts `X-Turnstile-Token` as a one-request fallback for an unverified UUID; failure returns 403
- Verification is cleared on WebSocket disconnect. The server secret comes from `TURNSTILE_SECRET_KEY`; the full-page site key is embedded in `index.html`, while widgets receive their public key through `window.bitbrushConfig`

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
- **Production smoke tests**: Playwright exercises the deployed production-site widget and Fly.io backend; it does not cover the full-page client or local runtime

Tests use the `test` profile (in-memory H2, `create-drop`).

## Key Design Decisions

- **Append-only pixel log**: `Pixel` table stores every placement; "current state" is derived via MAX(placedAt) subqueries. No UPDATE/DELETE on pixels.
- **No authentication**: Anonymous users identified by client-generated UUID. Username "You" is reserved.
- **Static clients without an app build**: The full-page client lives in `index.html`; the widget is a separate dependency-free JavaScript client. Node/npm is used only for Playwright E2E tooling.
- **Config as records**: `BitbrushProperties` is an immutable `@ConfigurationProperties` record with `@Validated` constraints.
- **Flyway migrations**: Docker and prod profiles use Flyway for schema management (`db/migration/`). Dev and test use Hibernate auto-DDL.
