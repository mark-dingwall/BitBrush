# BitBrush

A collaborative pixel art canvas where multiple users place colored pixels in real-time -- inspired by Reddit's r/Place.

A Java 21 + Spring Boot 3.5 application demonstrating real-time collaboration, WebSocket/STOMP messaging, persistent canvas state, and thread-safe in-memory placement banking.

Known follow-up work is tracked in the [project backlog](BACKLOG.md).

## Features

- **Real-time collaboration** -- pixel placements broadcast instantly via WebSocket/STOMP
- **215 drawable web-safe colors plus an eraser** in a 216-entry palette; the full-page picker is HSL-sorted and keyboard-selectable
- **Zoom, pan, and drag-to-place** with Bresenham line interpolation
- **Placement banking** -- earn points over time, spend them to place pixels
- **Canvas statistics and PNG export**
- **Mobile-responsive drawer** with touch support
- **Desktop keyboard controls and live announcements** for the full-page canvas
- **Cloudflare Turnstile** verification for first-time registration, with placement fallback checks for unverified users
- **Embeddable widget** (`bitbrush-widget.js`) with wheel/pinch zoom, touch and right-drag panning, grid overlay, and loading state
- **Fly.io deployment** with PostgreSQL, Flyway migrations, and health checks

## Architecture

- **Tech stack:** Java 21, Spring Boot 3.5, H2 for dev/test, PostgreSQL + Flyway for Docker/production, WebSocket/STOMP, vanilla HTML/JS/CSS
- **Architecture pattern:** Controller -> Service -> Repository (Spring MVC)
- **Real-time pattern:** REST POST -> Service -> SimpMessagingTemplate -> STOMP broker -> subscribed clients
- **State model:** append-only JPA pixel log with last-writer-wins canvas queries; JVM-local banking and connection state use concurrent maps

## Quick Start

Requires Docker Engine with the Compose plugin.

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080) in your browser. That's it.

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
fly deploy -a <app-name>
```

For the existing app, omit `-a <app-name>` and use its current `fly.toml` value. The public Turnstile site key is client configuration, not a server secret: register the deployment hostname with Cloudflare, replace the `TURNSTILE_SITE_KEY` constant in `index.html`, and supply the same public key as `turnstileSiteKey` to embedded widgets.

Health check: `GET /actuator/health`

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

The test suite includes unit tests (Mockito), controller slice tests (@WebMvcTest), repository slice tests (@DataJpaTest), integration tests (@SpringBootTest), and WebSocket tests (StompSession + CompletableFuture).

The Gradle test task also produces JaCoCo reports under `build/reports/jacoco/test/`; coverage is informational and is not enforced by a threshold.

### Production Widget Smoke Tests

The Playwright suite targets the live portfolio embed at `mark.dingwall.com.au` and the deployed backend at `bitbrush.fly.dev`. It requires Node.js, network access, and a Playwright browser install; it is separate from the local Gradle suite and interacts with the production deployment.

```bash
cd e2e
npm ci
npx playwright install chromium
npx playwright test
```
