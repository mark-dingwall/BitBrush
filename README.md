# BitBrush

A collaborative pixel art canvas where multiple users place colored pixels in real-time -- inspired by Reddit's r/Place.

A Java 21 + Spring Boot 3.5 application demonstrating real-time collaboration, WebSocket/STOMP messaging, and thread-safe in-memory state management.

<!-- TODO: Add demo GIF/screenshot -->

## Features

- **Real-time collaboration** -- pixel placements broadcast instantly via WebSocket/STOMP
- **216-color HSL-sorted web-safe palette** with keyboard navigation
- **Zoom, pan, and drag-to-place** with Bresenham line interpolation
- **Placement banking** -- earn points over time, spend them to place pixels
- **Canvas statistics and PNG export**
- **Mobile-responsive drawer** with touch support
- **ARIA-accessible keyboard controls**
- **Cloudflare Turnstile** bot protection on pixel placement
- **Embeddable widget** (`bitbrush-widget.js`) for cross-origin embedding with pinch-to-zoom, pan, and grid overlay
- **Fly.io deployment** with PostgreSQL, Flyway migrations, and health checks

## Architecture

- **Tech stack:** Java 21, Spring Boot 3.5, H2 Database, WebSocket/STOMP, vanilla HTML/JS/CSS
- **Architecture pattern:** Controller -> Service -> Repository (Spring MVC)
- **Real-time pattern:** REST POST -> Service -> SimpMessagingTemplate -> STOMP broker -> subscribed clients
- **Concurrency:** ConcurrentHashMap with atomic compute() for thread-safe placement banking

## Quick Start

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080) in your browser. That's it.

Canvas data persists across container restarts via a Docker volume.

### Embed as a Widget

Include the widget script on any page and configure it to point at your BitBrush server:

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

### Deploy to Fly.io

```bash
fly launch           # First-time setup (uses fly.toml defaults: syd region, 512MB)
fly secrets set DATABASE_URL=postgres://... TURNSTILE_SITE_KEY=... TURNSTILE_SECRET_KEY=...
fly deploy
```

Health check: `GET /actuator/health`

## Development

### Prerequisites

- Java 21 (install via [SDKMAN](https://sdkman.io/): `sdk install java 21-tem`)

### Run

```bash
./gradlew bootRun
```

Open [http://localhost:8080](http://localhost:8080). The dev profile uses a file-based H2 database at `./data/bitbrush-dev`.

### Test

```bash
./gradlew test
```

The test suite includes unit tests (Mockito), controller slice tests (@WebMvcTest), repository slice tests (@DataJpaTest), integration tests (@SpringBootTest), and WebSocket tests (StompSession + CompletableFuture).
