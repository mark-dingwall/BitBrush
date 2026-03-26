# BitBrush

A collaborative pixel art canvas where multiple users place colored pixels in real-time -- inspired by Reddit's r/Place.

Built with Java Spring Boot as a learning project for a developer transitioning from Laravel to Spring Boot.

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

<details>
<summary>Laravel to Spring Boot Concept Map</summary>

| Laravel | Spring Boot | Notes |
|---------|-------------|-------|
| `Eloquent Model` + `Migration` | `@Entity` + `spring.jpa.ddl-auto` | JPA annotations replace `$fillable`, `$casts`, migration files |
| `Route::resource()` | `@RequestMapping` + `@GetMapping`/`@PostMapping` | Annotations on controller methods, not a routes file |
| `Controller` | `@RestController` | Returns data directly (no view layer) |
| `FormRequest` | `@Valid` + DTO validation annotations | `@NotBlank`, `@Min`, `@Max` on record fields |
| `App\Exceptions\Handler` | `@RestControllerAdvice` | Catches exceptions globally, returns ProblemDetail (RFC 7807) |
| `public/` | `src/main/resources/static/` | Spring Boot serves static files from classpath |
| `Resource::toArray()` | Java `record` DTO | Immutable data carriers with automatic serialization |
| `.env` | `application-{profile}.properties` | Spring profiles replace single .env file |
| `config/*.php` | `@ConfigurationProperties` record | Type-safe, validated configuration binding |
| `php artisan serve` | `./gradlew bootRun` | Embedded Tomcat (no separate web server needed) |
| `php artisan test` | `./gradlew test` | JUnit 5 + Mockito + AssertJ |
| `php artisan test --filter` | `./gradlew test --tests "*ClassName"` | Filter by class or method name |
| `Log::info()` | SLF4J `log.info()` | Logging facade pattern (same concept, different API) |
| `$this->getJson()` | `mockMvc.perform(get(...))` | MockMvc simulates HTTP without a real server |
| `Mockery::mock()` | `@MockitoBean` / `@Mock` | `@MockitoBean` for Spring context, `@Mock` for pure unit tests |
| `RefreshDatabase` trait | `@Transactional` / `@DataJpaTest` | Auto-rollback after each test |
| Pusher + Laravel Echo | Built-in STOMP broker | No external service -- pub/sub runs inside the JVM |
| Redis rate limiter / `ThrottleRequests` | `ConcurrentHashMap.compute()` | Thread-safe in-memory rate limiting (no external cache) |
| `Laravel Sail` | `Docker Compose` | One-command development environment |
| Middleware | `Filter` / `Interceptor` | Not used in this project, but the equivalent exists |

</details>
