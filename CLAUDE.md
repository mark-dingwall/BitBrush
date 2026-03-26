# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is BitBrush

A collaborative pixel art canvas (like Reddit r/Place) built with Java 21 + Spring Boot 3.5. Users place colored pixels on a shared 250x250 grid in real-time via WebSocket/STOMP. Placement is rate-limited by a banking system that earns points over time. The frontend is a single-page vanilla HTML/JS/CSS file (`src/main/resources/static/index.html`) — there is no frontend build step.

This is a learning/portfolio project for a developer transitioning from Laravel to Spring Boot.

## Build & Run Commands

```bash
./gradlew bootRun          # Start dev server at http://localhost:8080 (dev profile, file-based H2)
./gradlew test             # Run all tests (unit, slice, integration, WebSocket)
./gradlew test --tests "*ClassName"        # Run a specific test class
./gradlew test --tests "*ClassName.methodName"  # Run a specific test method
docker compose up --build  # Container with PostgreSQL (docker profile)
```

The dev profile (`application-dev.properties`) uses `ddl-auto=create` — schema is dropped and recreated on each startup. The H2 console is available at `/h2-console` in dev.

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
- STOMP destinations: `/topic/pixels` (broadcasts), `/topic/users/count` (presence), `/queue/bank` (per-user balance via `convertAndSendToUser`)
- Client identity: UUID passed as STOMP CONNECT header, assigned as `Principal` by `WebSocketConfig`'s channel interceptor — required for `SimpUserRegistry` to work

**Banking system (in-memory, no DB):**
- `BankingService` uses `ConcurrentHashMap.compute()` for all mutations — thread-safe without locks
- Points earned on a `@Scheduled` timer (every `earnRateSeconds`), only for connected users
- Points reset on server restart (intentional design choice)
- Insufficient balance returns **402 Payment Required** (not 429) via `InsufficientBalanceException` → `GlobalExceptionHandler`

**Bot protection:**
- Cloudflare Turnstile verifies pixel placement requests via `X-Turnstile-Token` header
- `TurnstileService` posts to Cloudflare's siteverify endpoint; failure throws `TurnstileException` (→ 403)
- Site key and secret key configured via env vars (`TURNSTILE_SITE_KEY`, `TURNSTILE_SECRET_KEY`) with test-mode defaults

**CORS:**
- `CorsConfig` allows origins: `*.github.io`, `*.fly.dev`, `mark.dingwall.com.au`, `localhost:*`
- Allowed headers include `X-Turnstile-Token` for cross-origin widget requests

**Embeddable widget:**
- `bitbrush-widget.js` — standalone JS file that injects a full BitBrush canvas into any page
- Configured via `window.bitbrushConfig` (`server`, `container`, `turnstileSiteKey`)
- Supports pinch-to-zoom, pan, grid overlay at high zoom, and localStorage state persistence
- Designed for cross-origin embedding (e.g., GitHub Pages pointing at Fly.io backend)

**Eraser convention:** `paletteIndex == 0` is the eraser. All repository queries filter `WHERE paletteIndex <> 0` to exclude erased pixels from canvas state, stats, and author lookups.

**Database migrations:**
- Flyway manages schema for docker and prod profiles (`src/main/resources/db/migration/`)
- Dev and test profiles use Hibernate `ddl-auto` with `spring.flyway.enabled=false`

## Spring Profiles

| Profile | DB | Schema mgmt | H2 Console | Use case |
|---------|-----|-------------|------------|----------|
| `dev` | H2 file: `./data/bitbrush-dev` | `ddl-auto=create` | Yes `/h2-console` | Local development (`bootRun` default) |
| `test` | H2 in-memory | `ddl-auto=create-drop` | No | Test suite |
| `docker` | PostgreSQL (via docker-compose) | Flyway + `ddl-auto=validate` | No | Container deployment |
| `prod` | PostgreSQL (via `DATABASE_URL`) | Flyway + `ddl-auto=validate` | No | Fly.io production |

## Testing Patterns

- **Unit tests**: `@Mock` + `@InjectMocks` with Mockito (e.g., `BankingServiceTest`, `PixelServiceTest`, `CanvasExportServiceTest`)
- **Controller slice tests**: `@WebMvcTest` with `@MockitoBean` for isolated HTTP testing (e.g., `*SliceTest.java`)
- **Integration tests**: `@SpringBootTest` with `@Transactional` for full context (e.g., `CanvasControllerTest`, `PixelControllerTest`)
- **WebSocket tests**: `WebSocketIntegrationTest` uses `StompSession` + `CompletableFuture` against a live server
- **Repository tests**: `@DataJpaTest` with auto-rollback (e.g., `PixelRepositoryTest`, `UserRepositoryTest`)

Tests use the `test` profile (in-memory H2, `create-drop`).

## Key Design Decisions

- **Append-only pixel log**: `Pixel` table stores every placement; "current state" is derived via MAX(placedAt) subqueries. No UPDATE/DELETE on pixels.
- **No authentication**: Anonymous users identified by client-generated UUID. Username "You" is reserved.
- **Single HTML file**: All frontend code lives in `index.html` — CSS, JS, and HTML in one file. No npm, no bundler.
- **Config as records**: `BitbrushProperties` is an immutable `@ConfigurationProperties` record with `@Validated` constraints.
- **Flyway migrations**: Docker and prod profiles use Flyway for schema management (`db/migration/`). Dev and test use Hibernate auto-DDL.
