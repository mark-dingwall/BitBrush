# BitBrush

A collaborative pixel art canvas where multiple users place colored pixels in real-time -- inspired by Reddit's r/Place.

A Java 21 + Spring Boot 3.5 application with real-time collaboration, WebSocket/STOMP messaging, persistent canvas state, and thread-safe in-memory placement banking.

Known follow-up work is tracked in the [project backlog](BACKLOG.md).

## Quick Start

With Java 21 installed, run:

```bash
./gradlew bootRun
```

Open [http://localhost:8080](http://localhost:8080). The dev profile uses H2 and recreates its schema on every startup, so local users and canvas history are reset. Registration and recovery require matching Cloudflare Turnstile keys. See the [operations guide](docs/operations.md#turnstile-keys) for key configuration and the [Docker setup](docs/operations.md#run-with-docker) for a persistent local database.

## Features

- **Real-time collaboration** -- pixel placements broadcast instantly via WebSocket/STOMP
- **215 drawable web-safe colors plus an eraser** in a 216-entry palette; the full-page picker is HSL-sorted and keyboard-selectable
- **Zoom, pan, and drag-to-place** with Bresenham line interpolation
- **Placement banking** -- earn points over time, spend them to place pixels
- **Canvas statistics and PNG export**
- **Mobile-responsive drawer** with touch support
- **Desktop keyboard controls and live announcements** for the full-page canvas
- **Username recovery** using a case-sensitive four-character PIN, with separate public author IDs
- **Cloudflare Turnstile** verification for creation and PIN recovery, with placement fallback checks for unverified users
- **Embeddable widget** (`bitbrush-widget.js`) with wheel/pinch zoom, touch and right-drag panning, grid overlay, and loading state
- **Fly.io deployment** with PostgreSQL, Flyway migrations, and health checks

## Architecture

- **Tech stack:** Java 21, Spring Boot 3.5, H2 for dev/test, PostgreSQL + Flyway for Docker/production, WebSocket/STOMP, vanilla HTML/JS/CSS
- **Architecture pattern:** Controller -> Service -> Repository (Spring MVC)
- **Real-time pattern:** REST POST -> Service -> SimpMessagingTemplate -> STOMP broker -> subscribed clients
- **State model:** append-only JPA pixel log with last-writer-wins canvas queries; JVM-local banking and connection state use concurrent maps

## Embed as a Widget

Include the widget script on an allowed origin and configure it to point at your BitBrush server:

```html
<div id="bitbrush-container"></div>
<script>
  window.bitbrushConfig = {
    server: 'https://bitbrush.fly.dev',
    container: '#bitbrush-container',
    turnstileSiteKey: 'YOUR_SITE_KEY'
  };
</script>
<script src="https://bitbrush.fly.dev/bitbrush-widget.js"></script>
```

`turnstileSiteKey` is the public site key matching the backend's Turnstile secret and is required for first-time registration against a protected server. Cross-origin embeds must be allowlisted for both REST and WebSocket traffic; the shipped configuration allows HTTPS GitHub Pages and Fly.io origins, `https://mark.dingwall.com.au`, and HTTP localhost ports. For another origin, add the same origin pattern to `CorsConfig` and `WebSocketConfig`, then redeploy the backend.

## Identity API and credential handling

| POST endpoint | JSON request fields | Successful response |
|---|---|---|
| `/api/users` | `uuid`, `username`, `pin`, `pinConfirmation` | 201 with authoritative `uuid` and `username` |
| `/api/users/reconnect` | `uuid` | 200 with authoritative `uuid` and `username` |
| `/api/users/recover` | `username`, `pin` | 200 with the same private `uuid` and authoritative `username` |

Creation and recovery require `X-Turnstile-Token`. Reconnect authenticates the stored private UUID. Successful identity responses contain no PIN/hash and use `Cache-Control: no-store`. Identity POST bodies are limited to 4,096 bytes. Wrong PIN and unknown username receive the same 401 problem; throttling returns 429 with `Retry-After`, and exhausted hashing capacity returns 503 with `Retry-After`. PIN-less creation is rejected.

Usernames and PINs are case-sensitive. The server NFC-normalizes PINs, replaces Unicode control/format/separator characters with ordinary spaces, and requires exactly four Unicode code points; spaces remain significant. The only identity data stored locally is UUID/username: the full-page client uses `bitbrush_uuid`/`bitbrush_username`, and the widget uses `bitbrush_widget_uuid`/`bitbrush_widget_username`. PIN fields are transient and are cleared on success or mode changes. A private UUID is a bearer credential used only for identity, placement and STOMP CONNECT. Public pixel info, broadcasts and stored pixel authorship use `authorId`; that value cannot reconnect, authenticate STOMP or spend a bank.

Bank balances survive reconnect/recovery within one JVM, earn once per connected principal across tabs, and reset on restart. Identity verification remains valid for the process lifetime, including disconnects; validated STOMP reconnect restores verification after a restart.

Recovery canonicalizes the PIN before admission accounting. Credential failures are limited process-locally to five per exact username, while syntactically valid recovery attempts are limited to twenty per source IP in a rolling 15-minute window. Each map is bounded to 10,000 keys and fails closed when full. A successful recovery clears its account history; rejected hash-capacity work cancels only its own account reservation, retaining IP history. Run one application process: scaling to multiple machines requires shared admission/throttling state. Production trusts exactly one numeric `Fly-Client-IP` header; other profiles use the socket address.

## Development

See the [operations guide](docs/operations.md) for releases and [manual push-hook integration](docs/operations.md#optional-main-push-confirmation).

### Test

```bash
./gradlew test
```

The test suite includes unit tests (Mockito), controller slice tests (@WebMvcTest), repository slice tests (@DataJpaTest), real identity sequences (@SpringBootTest), and WebSocket tests (StompSession + CompletableFuture). `./gradlew clean build` is the full application gate. `./gradlew migrationTest` separately runs PostgreSQL/Testcontainers migration and constraint races; Docker is required and CI fails if it is unavailable.

The Gradle test task also produces JaCoCo reports under `build/reports/jacoco/test/`; coverage is informational and is not enforced by a threshold.

### Local browser checks

```bash
cd e2e
npm ci
npx playwright install chromium
npm run test:local
```

The local suite covers the full-page client and standalone widget using deterministic local HTTP, Turnstile and STOMP fixtures. It does not contact production or require a provisioned identity. CI gates the build, PostgreSQL migrations, these browser tests and the production container build.

## Argon2 calibration

Production and Docker use Argon2id with 19,456 KiB memory, 2 iterations, parallelism of 1, 32-byte output, and independent 16-byte salts. The [operations guide](docs/operations.md#pepper-and-argon2-operations) covers secret provisioning and hashing limits.

Run the opt-in calibration from the checkout root, with Docker available:

```bash
docker run --rm --memory=512m -e PIN_CALIBRATION=true -v "$(pwd):/workspace" -w /workspace gradle:8.14.4-jdk21 ./gradlew test --tests '*PinCredentialCalibrationTest' --no-daemon
```

The test warms up once, measures ten sequential hashes/verifications, and verifies two overlapping hashes behind a barrier. Only counts and timings go to `build/reports/pin-calibration.txt`. Record measurements on the target-class host before release; this container memory check does not measure the complete application's steady-state RSS. Never reduce the global production minima; increase cost only after this 512 MiB concurrent check remains healthy and latency remains acceptable.

On 2026-09-13, the exact command passed on local Docker Desktop with the production settings above and two overlapping hashes:

| Operation | Median | Maximum |
|---|---:|---:|
| Ten sequential hashes | 79.485 ms | 119.037 ms |
| Ten sequential verifications | 76.741 ms | 100.476 ms |
| Two concurrent hashes | 255.735 ms | 258.211 ms |

The 512 MiB memory-limited run had no OOM kill, but Docker's enabled swap was used during the cold Gradle build (sampled up to 326 MiB). Kernel cgroup peak accounting sampled 513.3 MiB around the 512 MiB limit. This is not a swap-free or full-application memory guarantee; repeat operational checks on the deployment host before raising costs. `gradle.properties` bounds build-daemon heap/metadata/code-cache/processor use, and `PIN_CALIBRATION=true` selects one Gradle worker and one 128 MiB test JVM. These runner settings do not change the production application's JVM or Argon2 parameters.
