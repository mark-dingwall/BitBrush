# BitBrush operations

See the [README](../README.md) for local development, embedding, and the Argon2 calibration procedure.

## Turnstile keys

The browser's public site key and the server's `TURNSTILE_SECRET_KEY` must belong to the same Cloudflare Turnstile configuration. Configure the full-page public key in `src/main/resources/static/index.html` and the widget's public key through `window.bitbrushConfig.turnstileSiteKey`. Setting the server-side `TURNSTILE_SITE_KEY` does not change either browser client.

For local development, use a matching pair from [Cloudflare's test-key documentation](https://developers.cloudflare.com/turnstile/troubleshooting/testing/). The dev profile defaults to the always-pass test secret. Use real keys for the deployed hostname in production. Store real secrets in a protected environment or secrets manager.

## Run with Docker

With Docker Engine and the Compose plugin installed, supply a backed-up [`PIN_PEPPER`](#pepper-and-argon2-operations) and a matching `TURNSTILE_SECRET_KEY` through your environment, then run:

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080). PostgreSQL canvas and identity data persist across restarts through a Docker volume.

## Deploy to Fly.io

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

For the existing app, omit `-a <app-name>` and use its current `fly.toml` value. Follow the [Turnstile key setup](#turnstile-keys) for the deployment hostname.

Health check: `GET /actuator/health`

## Credential-safe logging

Keep `org.springframework.messaging.simp`, `org.springframework.web.socket.messaging`, and `org.springframework.web.SimpLogging` at INFO or higher in every supported profile. Lowering any of these can expose credential-bearing STOMP diagnostics. Keep `org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF`: database constraint diagnostics can include private UUIDs and hashes even at ERROR. Do not enable HTTP body/header capture, JDBC bind logging, browser STOMP debug output, or credential-bearing tracing. Application errors use constant diagnostics.

## Pepper and Argon2 operations

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

## First deployment and legacy recovery delivery

V2 preserves historical UUID-shaped public author IDs; V3 rotates every old private UUID, derives a reproducible four-digit legacy PIN, and hashes it; V4 enforces the final non-null/unique schema. Existing browser UUIDs become stale and users must recover using operator-delivered PINs. Pixel history and usernames are preserved. New users choose their own PINs and are not included in the export.

1. Back up PostgreSQL and verify restoration into a disposable database. Securely back up `PIN_PEPPER`, then rehearse V1→V4 migration and corrective-release startup against that restored copy with production settings.
2. Set `PIN_BACKFILL_EXPORT_PATH=/tmp/bitbrush-pin-backfill/export.tsv` for the first deployment. Let the application create the dedicated directory as its runtime user with mode 0700; do not pre-create it as root. The protected target must be an owned, regular, non-symlink 0600 file. Existing byte-identical exports are accepted; unsafe or changed files fail startup without overwrite.
3. Confirm `[deploy] strategy = "immediate"` in `fly.toml`, then deploy. This accepts a brief maintenance window and prevents old/new writers overlapping the schema change. Do not override it with a rolling strategy.
4. Monitor Flyway, health and the export log's path, row count and machine ID. Retrieve the file from that exact machine over authenticated SSH/SFTP directly into protected operator storage, with local 0700 parent/0600 file modes. Never display the file in terminal output. If the machine disappears before retrieval, restart with the same pepper and export path to reproduce the identical mapping.
5. Deliver each legacy username/PIN through a private channel. Verify selected recoveries through the UI or a client that does not log requests/responses. Capture the recovered private UUID of a dedicated smoke-test identity and provision it as `BITBRUSH_E2E_UUID` in operator/CI secrets. Never reuse the old, publicly disclosed UUID or commit a PIN.
6. **Unset `PIN_BACKFILL_EXPORT_PATH` first**, apply the resulting restart/configuration change, then delete the exact remote export and remaining files in its dedicated directory on every surviving machine (or confirm the ephemeral filesystem is gone). Deleting first would let a restart recreate plaintext. Retain a delivery copy only as long as needed in protected storage; the pepper and database can reproduce legacy mappings.
7. Check create, reconnect, recovery, incorrect-PIN, capacity, throttle and multi-tab behavior. Run the separately provisioned production smoke suite only as an explicit deployment operation.

After V4, corrective rollback is **roll-forward only**. Do not restart an old binary: it cannot create complete identity rows. Preserve the current migrated database and pepper; prepare a corrective release compatible with all five identity columns. Rehearse it on a restored V4 copy, run `./gradlew clean build`, `./gradlew migrationTest`, the local browser suite and the production container build, then deploy with the same immediate strategy and verify health/identity workflows. A database correction must be a new Flyway migration tested on that copy; never edit applied V2–V4 migrations or attempt an application-only rollback. The automated PostgreSQL suite covers interrupted backfill rollback/retry and post-V4 application startup; restore/release rehearsal is an operator gate.

## Production Widget Smoke Tests

The Playwright suite targets the live production embed at `mark.dingwall.com.au` and the deployed backend at `bitbrush.fly.dev`. It requires Node.js, network access, and a Playwright browser install; it is separate from the local Gradle suite and interacts with the production deployment.

```bash
cd e2e
npm ci
npx playwright install chromium
npm run test:production
```

Provision `BITBRUSH_E2E_UUID` through your secret mechanism first; setup fails immediately if it is missing. Only the private UUID is preloaded, and `/api/users/reconnect` supplies the authoritative username. This suite is deliberately separate from local tests and is not a default CI gate.

## Tested-image CI and Fly releases

CI runs `clean build` **before** `migrationTest`, then deterministic browser tests,
then builds one `linux/amd64` image labelled with the checkout SHA. The backend
suite runs that image with PostgreSQL 17, production settings (including Argon2),
and a logging-disabled reverse proxy. Only `127.0.0.1:8080` is published; the
independent widget host uses `http://localhost:4174`. Database credentials and
pepper are generated for each disposable fixture. Cloudflare's
[official Turnstile test keys](https://developers.cloudflare.com/turnstile/troubleshooting/testing/)
are runtime-only: the full-page public key is replaced once in intercepted HTML,
and the widget fixture supplies its test public key. The packaged HTML is checked
before and after interception. Real browser tokens must pass real server-side
Siteverify, including creation and recovery, before canvas checks proceed. An
external-service outage fails verification; no mocked fallback is used.

To reproduce on a Docker-enabled machine with Java 21 and Node 22:

```bash
python3 ci/java-check.py build
python3 ci/java-check.py migration
python3 ci/verify-java-reporter.py
npm --prefix e2e ci
(cd e2e && npx playwright install --with-deps chromium)
node ci/verify-browser-reporter.mjs
python3 -m unittest discover -s ci/tests
npm --prefix e2e run test:local
mkdir -p .ci-runtime
export BITBRUSH_TEST_IMAGE=bitbrush:local-verification
docker build --platform linux/amd64 --label "org.opencontainers.image.revision=$(git rev-parse HEAD)" --iidfile .ci-runtime/tested-image-id -t "$BITBRUSH_TEST_IMAGE" .
trap 'python3 ci/backend.py down' EXIT INT TERM
python3 ci/backend.py up
npm --prefix e2e run test:backend
python3 ci/backend.py down
trap - EXIT INT TERM
```

Always remove the fixture and its disposable volumes, including after an
interruption. CI performs best-effort cleanup with an `always()` step; a forced
runner termination can prevent cleanup, so CI uses ephemeral hosted runners.
Do not point the backend suite at production.

Browser configurations use separate `test-output/{local,backend,smoke,portfolio}`
directories. Screenshots, video and traces are disabled. The safe browser reporter
publishes static source locations, outcomes, durations and constant error codes;
it ignores titles, exceptions, streams and attachments. CI's Gradle init script
similarly disables raw JUnit/HTML reports and test exception/stream logging and
emits allowlisted XML under `build/safe-results`. The `ci/java-check.py` wrapper
captures Gradle streams, including output outside test logging during JVM
shutdown, and emits only those safe results plus a constant command outcome.
Compiler and infrastructure failures therefore produce a constant code in CI.
The migration check fails if its results are missing, empty, failed or skipped;
a missing Docker daemon cannot silently turn it green. Synthetic credential failures
run in captured child processes. Each parent requires a private constant witness
written only by the intended failing assertion, in addition to its exact test
identity, failure code and sentinel checks; unrelated errors in that same test
are rejected. Temporary fixtures are never uploaded.
CI retains only explicit safe XML and application JaCoCo HTML/CSV paths for seven
days, even after failure. Never upload raw test directories, application logs,
DOM/error-context attachments, storage, request bodies or STOMP frames.

Successful eligible verification runs transfer the **original image** using
`docker save`, a SHA-256 archive checksum, image ID and revision label, retained
for one day. The dependent deployment job downloads only its own run's artifact,
checks the transfer, pushes a unique commit/run/attempt tag and resolves the
registry manifest digest. Fly deploys that digest without rebuilding, with
`immediate` strategy and `--ha=false`; existing health checks, VM sizing and
machine count remain configured in `fly.toml`. The image ID checks transfer
integrity; the registry digest identifies the release. Fly CLI is pinned to
0.5.11, and newly introduced artifact Actions are pinned to reviewed SHAs.

Deployment requires successful verification and `refs/heads/main`. A push also
requires repository variable `AUTO_DEPLOY_ENABLED=true`. Manual dispatch defaults
to verification only; `deploy=true` bypasses only that variable, and cannot deploy
a branch or failed verification. Freshness lookups compare the checkout to remote
`main` immediately before publication and deployment. Stale runs skip, and lookup
errors fail closed. Superseded verification jobs cancel within separate push,
PR and dispatch groups. Deployment, image/health verification and smoke share
one non-cancelling production concurrency group. GitHub may replace pending runs;
freshness checks remain authoritative. Re-run the whole workflow if another
attempt is needed; a deployment-only rerun cannot consume a previous attempt's
image artifact.

After deployment, CI allows three minutes for serving Machines to report the
expected digest and passing health checks. It also checks stopped/suspended
Machines with services because autostart can serve them later. It never prints
raw Machine configuration. Missing or incomplete configuration (including an
unreachable host) fails closed rather than hiding a potentially serving Machine.
The separate `npm --prefix e2e run test:smoke` suite
uses the dedicated `BITBRUSH_E2E_UUID` to reconnect the deployed full-page client
and a localhost-hosted widget, checking canvas retrieval, bank state and real
WebSocket/STOMP traffic. It creates no accounts, performs no recovery and places
no pixels; browser write interception permits only reconnect. The existing
portfolio suite remains an explicit `npm --prefix e2e run test:production`
operation. Release summaries contain the commit, expected/observed registry
digests and check outcomes. A failed deployment or smoke check needs investigation
and a roll-forward correction; there is no automatic rollback.

Before activating production releases, an operator must:

1. Verify PIN delivery and remote plaintext-export cleanup using the
   [legacy recovery procedure](#first-deployment-and-legacy-recovery-delivery).
   Confirm export generation is disabled before activating automated releases.
2. Create a GitHub **production** environment restricted to `main`, with an
   app-scoped `FLY_API_TOKEN` and `BITBRUSH_E2E_UUID` for the rotated private UUID
   of a dedicated smoke account. Neither secret belongs in verification jobs.
3. Merge with `AUTO_DEPLOY_ENABLED` absent or false.
4. Dispatch the current `main` with `deploy=true` and inspect full verification,
   deployment, serving-image and smoke results.
5. Enable `AUTO_DEPLOY_ENABLED=true` only after that rollout succeeds.

This change makes no full-load or memory-fit claim. Broad scanning, dependency
automation, browser matrices, coverage thresholds and general job parallelization
remain deferred.

## Optional main-push confirmation

Install per checkout with `sh ci/install-hooks.sh`. The installer refuses any
existing effective `core.hooksPath` (including global/system settings) or active
executable `pre-push` hook. For manual integration, keep your existing hook and
invoke this repository's `.githooks/pre-push` with the original arguments and
**all original stdin ref-update lines**, propagating failure; if multiple hooks
consume stdin, capture it in a private temporary file and replay it to each hook.

The hook prompts once for any update whose destination is `refs/heads/main`,
including explicit refspecs, deletions and multiple-ref pushes:

> You’re pushing directly to main. This may deploy to production. Continue? [y/N]

Only `y` or `yes` (case insensitive) from `/dev/tty` proceeds. Empty input, EOF or
no controlling terminal cancels. Other branches pass through. Automation can
explicitly set `BITBRUSH_ALLOW_MAIN_PUSH=1`; **agents must obtain user authorization
before using this override**. Git's `--no-verify` bypass also exists. This optional
local hook is a reminder, not a deployment admission or security boundary.
