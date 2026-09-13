# BitBrush

A collaborative pixel art canvas where multiple users place colored pixels in real-time -- inspired by Reddit's r/Place.

A Java 21 + Spring Boot 3.5 application with real-time collaboration, WebSocket/STOMP messaging, persistent canvas state, and thread-safe in-memory placement banking.

Known follow-up work is tracked in the [project backlog](BACKLOG.md).

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

## Quick Start

Requires Docker Engine with the Compose plugin and a backed-up `PIN_PEPPER` secret (see the operator instructions below). Compose rejects an unset or empty pepper before starting containers.

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080) in your browser. The clients ask for a username, PIN and PIN confirmation when creating an identity, or username and PIN when recovering one.

Canvas data persists across container restarts via a Docker volume.

### Embed as a Widget

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

### Deploy to Fly.io

The checked-in `fly.toml` targets the existing `bitbrush` app. For a fork, choose a globally unique app name, replace the `app` value in `fly.toml`, and create it before setting secrets:

```bash
fly auth login
fly apps create <app-name>
```

Provision PostgreSQL next. For Fly Managed Postgres, `fly mpg create --name <database-name> --region syd` creates a cluster and prints a pooled connection string. This application does not consume that combined `postgresql://` string directly: convert its scheme to `jdbc:postgresql://` and split its username and password into the three Spring datasource secrets below.

```bash
fly secrets set -a <app-name> \
  DATABASE_URL='jdbc:postgresql://<host>:5432/<database>' \
  DATABASE_USERNAME='<username>' \
  DATABASE_PASSWORD='<password>' \
  TURNSTILE_SECRET_KEY='<secret>'
# Import PIN_PEPPER from protected secret-manager input before deploying.
fly deploy -a <app-name>
```

For the existing app, omit `-a <app-name>` and use its current `fly.toml` value. The public Turnstile site key is client configuration, not a server secret: register the deployment hostname with Cloudflare, replace the `TURNSTILE_SITE_KEY` constant in `index.html`, and supply the same public key as `turnstileSiteKey` to embedded widgets.

Health check: `GET /actuator/health`

### Identity API and credential handling

| POST endpoint | JSON request fields | Successful response |
|---|---|---|
| `/api/users` | `uuid`, `username`, `pin`, `pinConfirmation` | 201 with authoritative `uuid` and `username` |
| `/api/users/reconnect` | `uuid` | 200 with authoritative `uuid` and `username` |
| `/api/users/recover` | `username`, `pin` | 200 with the same private `uuid` and authoritative `username` |

Creation and recovery require `X-Turnstile-Token`. Reconnect authenticates the stored private UUID. Successful identity responses contain no PIN/hash and use `Cache-Control: no-store`. Identity POST bodies are limited to 4,096 bytes. Wrong PIN and unknown username receive the same 401 problem; throttling returns 429 with `Retry-After`, and exhausted hashing capacity returns 503 with `Retry-After`. PIN-less creation is rejected.

Usernames and PINs are case-sensitive. The server NFC-normalizes PINs, replaces Unicode control/format/separator characters with ordinary spaces, and requires exactly four Unicode code points; spaces remain significant. The only identity data stored locally is UUID/username: the full-page client uses `bitbrush_uuid`/`bitbrush_username`, and the widget uses `bitbrush_widget_uuid`/`bitbrush_widget_username`. PIN fields are transient and are cleared on success or mode changes. A private UUID is a bearer credential used only for identity, placement and STOMP CONNECT. Public pixel info, broadcasts and stored pixel authorship use `authorId`; that value cannot reconnect, authenticate STOMP or spend a bank.

Bank balances survive reconnect/recovery within one JVM, earn once per connected principal across tabs, and reset on restart. Identity verification remains valid for the process lifetime, including disconnects; validated STOMP reconnect restores verification after a restart.

Recovery limits are process-local: five attempts per exact username and twenty requests per source IP in a rolling 15-minute window. Each map is bounded to 10,000 keys and fails closed when full. A successful recovery clears its account history; rejected hash-capacity work cancels only its own account reservation, retaining IP history. Run one application process: scaling to multiple machines requires shared admission/throttling state. Production trusts exactly one numeric `Fly-Client-IP` header; other profiles use the socket address.

Keep `org.springframework.messaging.simp`, `org.springframework.web.socket.messaging`, and `org.springframework.web.SimpLogging` at INFO or higher in every supported profile. Lowering any of these can expose credential-bearing STOMP diagnostics. Keep `org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF`: database constraint diagnostics can include private UUIDs and hashes even at ERROR. Do not enable HTTP body/header capture, JDBC bind logging, browser STOMP debug output, or credential-bearing tracing. Application errors use constant diagnostics.

### Pepper and Argon2 operations

`PIN_PEPPER` must be Base64 encoding of at least 32 cryptographically random bytes, provisioned separately from PostgreSQL. Generate it in a secrets manager, or generate directly into a protected file with shell tracing/session capture disabled:

```bash
PIN_SECRET_DIR="$(mktemp -d)"
chmod 700 "$PIN_SECRET_DIR"
umask 077
openssl rand -base64 32 > "$PIN_SECRET_DIR/pin-pepper"
export PIN_PEPPER="$(< "$PIN_SECRET_DIR/pin-pepper")"
```

Back up this exact secret in encrypted operator storage before starting the application; never commit it or use the explicit development/test fixture pepper in Docker/production. Import it through protected stdin with `fly secrets import` (a `PIN_PEPPER=<value>` record), or the Fly secrets UI. Keep the database backup and pepper backup separate. Losing or changing the pepper makes existing PIN hashes unverifiable and prevents reproducing legacy PINs; there is no supported transparent pepper rotation or PIN reset. Possession of an existing private UUID still permits reconnect.

Production and Docker use Argon2id memory 19,456 KiB, iterations 2, parallelism 1, 32-byte output and independent 16-byte salts. The HMAC-SHA-256 prehash and legacy derivation use distinct versioned domains. A process-wide semaphore admits at most two real/dummy operations and rejects excess work immediately. Dev/test use cheap explicit parameters solely for tests and local development.

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

### First deployment and legacy recovery delivery

V2 preserves historical UUID-shaped public author IDs; V3 rotates every old private UUID, derives a reproducible four-digit legacy PIN, and hashes it; V4 enforces the final non-null/unique schema. Existing browser UUIDs become stale and users must recover using operator-delivered PINs. Pixel history and usernames are preserved. New users choose their own PINs and are not included in the export.

1. Back up PostgreSQL and verify restoration into a disposable database. Securely back up `PIN_PEPPER`, then rehearse V1→V4 migration and corrective-release startup against that restored copy with production settings.
2. Set `PIN_BACKFILL_EXPORT_PATH=/tmp/bitbrush-pin-backfill/export.tsv` for the first deployment. Let the application create the dedicated directory as its runtime user with mode 0700; do not pre-create it as root. The protected target must be an owned, regular, non-symlink 0600 file. Existing byte-identical exports are accepted; unsafe or changed files fail startup without overwrite.
3. Confirm `[deploy] strategy = "immediate"` in `fly.toml`, then deploy. This accepts a brief maintenance window and prevents old/new writers overlapping the schema change. Do not override it with a rolling strategy.
4. Monitor Flyway, health and the export log's path, row count and machine ID. Retrieve the file from that exact machine over authenticated SSH/SFTP directly into protected operator storage, with local 0700 parent/0600 file modes. Never display the file in terminal output. If the machine disappears before retrieval, restart with the same pepper and export path to reproduce the identical mapping.
5. Deliver each legacy username/PIN through a private channel. Verify selected recoveries through the UI or a client that does not log requests/responses. Capture the recovered private UUID of a dedicated smoke-test identity and provision it as `BITBRUSH_E2E_UUID` in operator/CI secrets. Never reuse the old, publicly disclosed UUID or commit a PIN.
6. **Unset `PIN_BACKFILL_EXPORT_PATH` first**, apply the resulting restart/configuration change, then delete the exact remote export and remaining files in its dedicated directory on every surviving machine (or confirm the ephemeral filesystem is gone). Deleting first would let a restart recreate plaintext. Retain a delivery copy only as long as needed in protected storage; the pepper and database can reproduce legacy mappings.
7. Check create, reconnect, recovery, incorrect-PIN, capacity, throttle and multi-tab behavior. Run the separately provisioned production smoke suite only as an explicit deployment operation.

After V4, corrective rollback is **roll-forward only**. Do not restart an old binary: it cannot create complete identity rows. Preserve the current migrated database and pepper; prepare a corrective release compatible with all five identity columns. Rehearse it on a restored V4 copy, run `./gradlew clean build`, `./gradlew migrationTest`, the local browser suite and the production container build, then deploy with the same immediate strategy and verify health/identity workflows. A database correction must be a new Flyway migration tested on that copy; never edit applied V2–V4 migrations or attempt an application-only rollback. The automated PostgreSQL suite covers interrupted backfill rollback/retry and post-V4 application startup; restore/release rehearsal is an operator gate.

## Development

### Prerequisites

- Java 21 (install via [SDKMAN](https://sdkman.io/): `sdk install java 21-tem`)

### Run

```bash
./gradlew bootRun
```

Open [http://localhost:8080](http://localhost:8080). The dev profile uses file-backed H2 at `./data/bitbrush-dev`, but Hibernate recreates the schema on every dev startup.

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

### Production Widget Smoke Tests

The Playwright suite targets the live production embed at `mark.dingwall.com.au` and the deployed backend at `bitbrush.fly.dev`. It requires Node.js, network access, and a Playwright browser install; it is separate from the local Gradle suite and interacts with the production deployment.

```bash
cd e2e
npm ci
npx playwright install chromium
npm run test:production
```

Provision `BITBRUSH_E2E_UUID` through your secret mechanism first; setup fails immediately if it is missing or malformed. Only the private UUID is preloaded, and `/api/users/reconnect` supplies the authoritative username. This suite is deliberately separate from local tests and is not a default CI gate.
