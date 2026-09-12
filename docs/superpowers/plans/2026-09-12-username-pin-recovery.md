# Username PIN Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add recoverable username identities backed by four-code-point Unicode PINs while separating private bearer UUIDs from public canvas author IDs.

**Architecture:** `UserIdentityService` owns create, reconnect, and recover orchestration; `PinCredentialService` owns canonicalization and Argon2id credential work; focused services own throttling, client-IP resolution, and bounded request bodies. PostgreSQL migration rotates disclosed legacy UUIDs, preserves them as public author IDs, hashes reproducible legacy PINs, and a post-migration runner publishes the requested protected export. Spring's authenticated STOMP principal and `SimpUserRegistry` remain the WebSocket and banking presence authorities.

**Tech Stack:** Java 21, Spring Boot 3.5.11, Spring MVC/JPA/WebSocket, Flyway, PostgreSQL/H2, Spring Security Crypto `Argon2PasswordEncoder` with Bouncy Castle, JUnit 5/Mockito/AssertJ, Testcontainers PostgreSQL, vanilla JavaScript, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-12-username-pin-recovery-design.md`

## Global Constraints

- A PIN is case-sensitive and must canonicalize to exactly four Unicode code points.
- Canonicalization is NFC, then replaces every `Cc`, `Cf`, `Zs`, `Zl`, and `Zp` code point with U+0020; spaces remain significant and unpaired surrogates are rejected.
- PIN inputs are never stored by either client and never appear in URLs, logs, responses, metrics, tracing attributes, or test output.
- Credential input is HMAC-SHA-256 over `bitbrush-pin-v1` plus canonical UTF-8 using a server-wide pepper, then Argon2id with a unique salt.
- `PIN_PEPPER` is Base64-encoded, decodes to at least 32 bytes, is mandatory in docker/prod, and has explicit non-production dev/test values.
- Argon2id production defaults are 19,456 KiB memory, 2 iterations, parallelism 1, 32-byte output, and at most two concurrent operations; calibration may increase but never reduce these values.
- Argon2 capacity acquisition is immediate; exhaustion returns `503 Service Unavailable` with `Retry-After: 1`.
- Recovery permits five failed attempts per exact username and twenty total requests per canonical source IP in rolling 15-minute windows.
- The recovery maps are process-local and bounded to 10,000 live keys each; capacity exhaustion fails closed.
- Identity POST bodies are capped at 4,096 bytes before MVC, Turnstile, or Argon2; raw PIN fields are capped at 256 UTF-16 code units before credential work.
- New public author IDs are `author_` plus unpadded base64url for 24 random bytes (192 random bits), syntactically disjoint from canonical UUIDs.
- Private IDs accept only `UUID.fromString(value).toString().equals(value)` canonical values.
- Creation retries public-author-ID constraint collisions at most five times and never reuses a failed transaction.
- Every response containing a private UUID has `Cache-Control: no-store`.
- Existing UUID-shaped public author IDs remain public history only and must fail every bearer-credential path.
- Turnstile verification state lasts for the process lifetime; disconnect never clears it.
- Banking earns once per unique `SimpUserRegistry` principal, regardless of that principal's session count.
- The widget remains standalone and dependency-free; the two clients may mirror small identity UI code.
- Database/filesystem rollout is one cohesive feature because no intermediate production schema is safe for mixed old/new application writes.

---

## File Structure and Ownership

- `config/PinProperties.java` defines credential and throttle configuration only.
- `service/PinCredentialCodec.java` contains deterministic canonicalization, HMAC, Argon2 encoding/verification, and legacy PIN derivation shared by runtime and migration.
- `service/PinCredentialService.java` is the sole application-facing credential service and owns the fair global permit pool and dummy verification hash.
- `service/RecoveryAttemptService.java` owns atomic rolling-window state only.
- `service/ClientIpResolver.java` owns trusted source-address selection and IP canonicalization only.
- `config/IdentityRequestSizeFilter.java` owns the three-endpoint 4 KiB boundary and replayable request wrapper only.
- `service/AuthorIdGenerator.java` owns cryptographically random public ID generation only.
- `service/UserIdentityService.java` owns complete create/reconnect/recover workflows only.
- `service/LegacyPinExportRunner.java` owns startup orchestration for export; `service/SecureExportPublisher.java` owns filesystem validation and publication.
- `db/migration/V2__add_identity_credential_columns.sql`, `db/migration/V3__Backfill_user_pin_credentials.java`, and `db/migration/V4__enforce_identity_constraints.sql` form the production migration sequence.
- Existing `PixelService`, WebSocket configuration/listener, and `BankingService` retain their current focused responsibilities.
- Existing `index.html` and `bitbrush-widget.js` each own their UI implementation; neither gains a shared runtime dependency.

### Task 1: PIN Credential Primitive and Configuration

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/config/PinProperties.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/config/PinProductionPolicy.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/service/PinCredentialCodec.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/service/PinCredentialService.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/exception/InvalidPinException.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/exception/PinCapacityException.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-test.properties`
- Modify: `src/main/resources/application-docker.properties`
- Modify: `src/main/resources/application-prod.properties`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/config/PinPropertiesTest.java`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/config/PinProductionPolicyTest.java`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/service/PinCredentialCodecTest.java`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/service/PinCredentialServiceTest.java`

**Interfaces:**
- Consumes: Base64 `PIN_PEPPER`; UTF-16 Java strings at the trust boundary.
- Produces: `PinCredentialCodec.CanonicalPin`, `canonicalize(String)`, `hash(CanonicalPin)`, `verify(CanonicalPin, String)`, and `deriveLegacyPin(String)`; application facade `canonicalize`, `hash`, `verify`, `verifyDummy`, and `matchesConfirmation`.

- [ ] **Step 1: Add failing canonicalization and derivation tests**

  Create parameterized cases for ASCII, `A😀b!`, mixed scripts, case differences, NFC composed/decomposed input, and each replaced category. Add explicit assertions for leading/trailing spaces, three/five post-normalization code points, null/empty/over-256 input, and isolated high/low surrogates. Define the stable interface in the test:

  ```java
  PinCredentialCodec codec = new PinCredentialCodec(pepperBytes, 32, 1, 1, 32);
  CanonicalPin pin = codec.canonicalize("Ae\u0301 !");
  assertThat(pin.value()).isEqualTo("Aé !");
  assertThat(pin.utf8()).isEqualTo("Aé !".getBytes(StandardCharsets.UTF_8));
  assertThat(codec.deriveLegacyPin("legacy-author-id")).matches("\\d{4}");
  ```

- [ ] **Step 2: Run credential tests and verify the missing types fail compilation**

  Run: `./gradlew test --tests '*PinCredentialCodecTest' --tests '*PinPropertiesTest' --tests '*PinProductionPolicyTest'`

  Expected: FAIL because the credential types do not exist.

- [ ] **Step 3: Add the maintained pure-Java Argon2 implementation and validated properties**

  Add `implementation("org.springframework.security:spring-security-crypto")` (version-managed by Spring Boot 3.5.11) and `implementation("org.bouncycastle:bcprov-jdk18on:1.86")` (not managed by the Boot BOM). Define:

  ```java
  @Validated
  @ConfigurationProperties(prefix = "pin")
  public record PinProperties(
      @NotBlank String pepper,
      @Min(8) int memoryKiB,
      @Min(1) int iterations,
      @Min(1) int parallelism,
      @Min(16) int hashLength,
      @Min(1) int maxConcurrent,
      @Min(1) int retryAfterSeconds,
      @Min(1) int accountCapacity,
      @Min(1) int ipCapacity) {}
  ```

  `PinProductionPolicy` decodes and validates the pepper at startup for every profile. It additionally rejects memory below 19,456 KiB, iterations below 2, parallelism below 1, hash length below 32, or concurrency above 2 when either `prod` or `docker` is active; this permits cheap explicit test parameters without weakening deployed profiles. Use `19456/2/1/32/2/1/10000/10000` in docker/prod. Put a clearly labelled Base64 encoding of 32 zero bytes and cheaper work parameters only in dev/test. Docker/prod use `${PIN_PEPPER}` with no fallback and configure the same value as Flyway placeholder `pin-pepper`.

- [ ] **Step 4: Implement the lower-level codec**

  `CanonicalPin` defensively copies its UTF-8 byte array. Reject only unpaired UTF-16 high or low surrogates while accepting valid high/low pairs, NFC-normalize, replace the five categories by Unicode code point, require exactly four code points, and preserve spaces/case. HMAC input is length-unambiguous:

  ```java
  mac.update("bitbrush-pin-v1\0".getBytes(StandardCharsets.US_ASCII));
  byte[] keyed = mac.doFinal(canonicalPin.utf8());
  char[] argonInput = Base64.getUrlEncoder().withoutPadding()
      .encodeToString(keyed).toCharArray();
  ```

  Construct `new Argon2PasswordEncoder(16, hashLength, parallelism, memoryKiB, iterations)`. Pass `CharBuffer.wrap(argonInput)` to `encode` and `matches`, wipe keyed/base64 byte arrays and `argonInput` in `finally`, and store only the encoder's standard Argon2id value containing version, configured work parameters, random salt, and digest. Derive legacy PIN using HMAC domain `bitbrush-legacy-pin-v1\0` and rejection sampling from unsigned 32-bit chunks below `floor(2^32 / 10000) * 10000`; expand with an appended big-endian counter if the digest is exhausted.

- [ ] **Step 5: Add failing facade tests for salts, pepper, permits, and dummy work**

  Use a fake codec whose active-call counter is held behind latches. Assert different hashes for the same PIN, correct/wrong PIN and case, wrong pepper, encoded algorithm/parameters, no raw PIN/pepper material, confirmation after canonicalization, real/dummy shared permit limit, immediate capacity failure, interrupted operation, and permit release after success and exception.

- [ ] **Step 6: Implement `PinCredentialService` and domain exceptions**

  Expose exactly:

  ```java
  public CanonicalPin canonicalize(String rawPin)
  public boolean matchesConfirmation(String pin, String confirmation)
  public String hash(CanonicalPin pin)
  public boolean verify(CanonicalPin pin, String encodedHash)
  public void verifyDummy(CanonicalPin pin)
  public String deriveLegacyPin(String authorId)
  ```

  Construct one dummy encoded hash at startup. Wrap every real hash, real verification, and dummy verification in the same fair `Semaphore`; use `tryAcquire()` without waiting and release in `finally`. Throw `InvalidPinException` without echoing input and `PinCapacityException(retryAfterSeconds)` without logging secrets.

- [ ] **Step 7: Run focused tests and commit**

  Run: `./gradlew test --tests '*PinPropertiesTest' --tests '*PinProductionPolicyTest' --tests '*PinCredentialCodecTest' --tests '*PinCredentialServiceTest'`

  Expected: PASS.

  ```bash
  git add build.gradle.kts src/main/java src/main/resources/application-*.properties src/test/java
  git commit -m "feat: add PIN credential processing"
  ```

### Task 2: Atomic Recovery Throttling

**Files:**
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/service/RecoveryAttemptService.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/config/ClockConfig.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/exception/RecoveryThrottledException.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/exception/RecoveryCapacityException.java`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/service/RecoveryAttemptServiceTest.java`

**Interfaces:**
- Consumes: `PinProperties.accountCapacity/ipCapacity` and injected `Clock`.
- Produces: `recordIpAttempt(InetAddress)`, `recordAccountAttempt(String)`, and `clearAccount(String)`.

- [ ] **Step 1: Write failing deterministic throttle and concurrency tests**

  Use `MutableClock` and barriers. For username and IP independently assert immediately below/at/after limits, exact rolling expiry, success clearing username only, exact case sensitivity, unknown username accounting, key independence, bounded-map fail-closed behavior, and cleanup racing with checks. The concurrency assertion is:

  ```java
  AtomicInteger accepted = new AtomicInteger();
  IntStream.range(0, 30).parallel().forEach(i -> {
      try {
          service.recordIpAttempt(ip);
          accepted.incrementAndGet();
      } catch (RecoveryThrottledException ignored) {
          // Expected after the twentieth accepted request.
      }
  });
  assertThat(accepted).hasValue(20);
  ```

- [ ] **Step 2: Implement atomic rolling windows**

  Store immutable timestamp deques in `ConcurrentHashMap.compute`; prune timestamps `<= now.minus(15 minutes)`, reject before adding when the applicable limit is already present, and compute ceiling seconds for `Retry-After`. Allocate a new key only after an atomic capacity reservation; release reservations when entries expire or are cleared. Expose package-private `cleanupExpired()` for deterministic tests and scheduled opportunistic cleanup.

- [ ] **Step 3: Run focused tests and commit**

  Run: `./gradlew test --tests '*RecoveryAttemptServiceTest'`

  Expected: PASS.

  ```bash
  git add src/main/java src/test/java
  git commit -m "feat: throttle identity recovery attempts"
  ```

### Task 3: Identity HTTP Perimeter

**Files:**
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/service/ClientIpResolver.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/config/IdentityRequestSizeFilter.java`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/service/ClientIpResolverTest.java`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/config/IdentityRequestSizeFilterTest.java`

**Interfaces:**
- Consumes: servlet request method, path, headers, remote address, content length, and body.
- Produces: `ClientIpResolver.resolve(HttpServletRequest): InetAddress` and a filter ordered before MVC.

- [ ] **Step 1: Write and implement client-IP tests**

  In `prod`, require exactly one parseable `Fly-Client-IP` value; reject comma lists, zone identifiers, hostnames, and malformed values. Outside `prod`, ignore that header and parse `request.getRemoteAddr()`. Parse numeric literals without accepting DNS names and return `InetAddress` so `getHostAddress()` gives canonical IPv4/IPv6 throttle keys.

- [ ] **Step 2: Write failing 4 KiB filter tests**

  Test only POST `/api/users`, `/api/users/reconnect`, `/api/users/recover`; a 4,096-byte unknown-length request must reach the chain, while 4,097 bytes and declared lengths over 4,096 return ProblemDetail `413` and never invoke the chain. GET and unrelated POST requests pass through untouched.

- [ ] **Step 3: Implement bounded buffering and replay**

  Extend `OncePerRequestFilter`. Check `Content-Length` first; otherwise read through a fixed 4,097-byte buffer, reject overflow, or wrap the bytes in an `HttpServletRequestWrapper` whose input stream and reader can replay the body. Write `application/problem+json` with title `Content Too Large` and a generic detail.

- [ ] **Step 4: Run focused tests and commit**

  Run: `./gradlew test --tests '*ClientIpResolverTest' --tests '*IdentityRequestSizeFilterTest'`

  Expected: PASS.

  ```bash
  git add src/main/java src/test/java
  git commit -m "feat: protect identity HTTP boundaries"
  ```

### Task 4: Private/Public Identity Persistence and Canvas Authorship

**Files:**
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/model/User.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/model/Pixel.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/repository/UserRepository.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/repository/PixelRepository.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/dto/PixelBroadcast.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/dto/PixelInfoResponse.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/service/AuthorIdGenerator.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/service/PixelService.java`
- Modify: related existing pixel/canvas/repository tests

**Interfaces:**
- Consumes: private UUID from `PixelPlacementRequest.authorUuid()`.
- Produces: `User.authorId`, `User.pinHash`, `User.pinBackfilled`; public `Pixel.authorId`; `AuthorIdGenerator.generate(): String`; `PixelBroadcast.authorId()` and `PixelInfoResponse.authorId()`.

- [ ] **Step 1: Expand repository tests before entities**

  Build users with all five fields and assert exact-case `findByUsername`, `existsByAuthorId`, non-null fields, and unique username/private UUID/public ID. Add pixel repository tests proving current-author queries use public `authorId` and still work for UUID-shaped legacy author IDs.

- [ ] **Step 2: Modify entities and repositories**

  Give `User` fields `uuid`, `username`, `authorId`, `pinHash`, `pinBackfilled`; name table constraints `uk_users_username` and `uk_users_author_id` and keep the private UUID primary key named by migration as `pk_users_uuid`. Because UUID is assigned before persistence and identities are insert-only, implement `Persistable<String>` with transient `isNew=true`, `getId()` returning UUID, and a `@PostLoad`/`@PostPersist` callback setting `isNew=false`; this makes Spring Data choose `EntityManager.persist` rather than `merge` for newly constructed users. Add a repository test proving a newly constructed duplicate UUID raises the primary-key violation instead of updating the existing identity. Rename the Java/column pixel property to `authorId`. Add:

  ```java
  Optional<User> findByUsername(String username);
  boolean existsByUsername(String username);
  boolean existsByAuthorId(String authorId);
  List<User> findAllByPinBackfilledTrueOrderByUsernameAsc();
  ```

- [ ] **Step 3: Test and implement public ID generation**

  Inject `SecureRandom`, generate 24 bytes, and return `author_` plus unpadded URL-safe Base64. Assert 10,000 generated values are unique in the test sample, match `author_[A-Za-z0-9_-]{32}`, and cannot parse as UUIDs.

- [ ] **Step 4: Write failing pixel-service privacy tests**

  Given a request containing a private UUID and a repository user with `authorId`, assert saved pixels, broadcasts, info responses, and author-highlight queries contain only the public ID. Assert a public author ID supplied as bearer input fails lookup and writes/deducts nothing. Capture debug logs and assert private UUID absence.

- [ ] **Step 5: Refactor `PixelService` to resolve once and persist public authorship**

  Replace `existsById` with `findById(request.authorUuid()).orElseThrow(() -> new UserNotFoundException(request.authorUuid()))`, perform it before banking deduction, then use `user.getAuthorId()` for `Pixel.authorId`, `PixelBroadcast.authorId`, username resolution, and current-author query. Remove credential-bearing log arguments. Retain `userExists` and `registerUser` temporarily so the unchanged controller compiles; Task 6 removes them atomically with their final callers.

- [ ] **Step 6: Run repository and canvas tests and commit**

  Run: `./gradlew test --tests '*UserRepositoryTest' --tests '*PixelRepositoryTest' --tests '*PixelServiceTest' --tests '*PixelController*' --tests '*CanvasController*' --tests '*StatsController*'`

  Expected: PASS.

  ```bash
  git add src/main/java src/test/java
  git commit -m "feat: separate private and public user identifiers"
  ```

### Task 5: Identity Workflow Service

**Files:**
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/dto/UserCreateRequest.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/dto/UserReconnectRequest.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/dto/UserRecoveryRequest.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/dto/UserIdentityResponse.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/validation/CanonicalUuid.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/validation/CanonicalUuidValidator.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/service/UserIdentityService.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/exception/DuplicateIdentityException.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/exception/InvalidCredentialsException.java`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/service/UserIdentityServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1–4 services/repository and `TurnstileService.verify/markVerified`.
- Produces:

  ```java
  UserIdentityResponse create(UserCreateRequest request, String turnstileToken)
  UserIdentityResponse reconnect(UserReconnectRequest request)
  UserIdentityResponse recover(UserRecoveryRequest request, String turnstileToken, InetAddress sourceIp)
  ```

- [ ] **Step 1: Define validated DTOs, canonical UUID validation, and failing orchestration tests**

  Add a field annotation whose validator delegates to `public static boolean CanonicalUuidValidator.isCanonical(String value)`, implemented with `UUID.fromString(value).toString().equals(value)`; null/blank remains the DTO's `@NotBlank` responsibility. `UserCreateRequest` has canonical UUID, existing username constraints, and `@Size(max=256)` PIN fields; reconnect has canonical UUID; recovery has existing username constraints and `@Size(max=256)` PIN. Creation tests assert `canonicalize/compare → verify Turnstile → uniqueness prechecks → hash → transaction commit → markVerified`. Recovery tests assert `canonicalize → IP record → verify Turnstile → account record → lookup → real/dummy verify → clear account on success → markVerified`. Every failure before successful commit/credential verification omits `markVerified`.

- [ ] **Step 2: Add creation success, collision, and transaction-failure tests**

  Cover reserved `You`, exact username conflict, private UUID collision in either identifier column, invalid/noncanonical UUID, generated public-ID syntax, forced author-ID collision followed by success, five author-ID collisions, named UUID/username constraint races, unrelated integrity errors, flush failure, and commit-time failure. Add a staggered same-UUID race that pauses request B after its precheck, commits request A, then releases B; assert B performs insert-only persistence, receives the named primary-key conflict, and cannot modify A's username, public author ID, or PIN hash. Assert each public-ID retry calls a new `TransactionTemplate.execute` and only one committed UUID is marked verified.

- [ ] **Step 3: Implement create with explicit transaction boundaries**

  The service itself has no `@Transactional`. Precheck, then execute one complete insert-only `saveAndFlush` attempt per generated author ID; Task 4's `Persistable.isNew()` contract must cause `persist`, never `merge`, for the constructed identity. Classify only `pk_users_uuid`, `uk_users_username`, and `uk_users_author_id` by walking nested `SQLException`/Hibernate constraint exceptions. Retry only `uk_users_author_id`, map UUID/username to `DuplicateIdentityException`, and rethrow all others. Call `markVerified` after `TransactionTemplate.execute` returns.

- [ ] **Step 4: Add reconnect and recovery tests**

  Assert reconnect returns authoritative username without mutation and marks only a found canonical private UUID. For recovery assert IP accounting precedes Turnstile; account accounting follows successful Turnstile; unknown username calls dummy Argon2; wrong and unknown credentials throw the same exception; success clears only the account counter, preserves IP history, returns the private UUID, and marks it only after verification.

- [ ] **Step 5: Implement reconnect and recover**

  Canonicalize PIN before expensive work. Use exact-case `findByUsername`. Run real or dummy verification through `PinCredentialService`; never include username, UUID, PIN, or hash in exception text. Retain existing `TurnstileService.verify` and `markVerified`; remove `verifyAndRemember` only after all callers have migrated.

- [ ] **Step 6: Run service tests and commit**

  Run: `./gradlew test --tests '*UserIdentityServiceTest' --tests '*PixelServiceTest' --tests '*TurnstileServiceTest'`

  Expected: PASS.

  ```bash
  git add src/main/java src/test/java
  git commit -m "feat: add user identity workflows"
  ```

### Task 6: Identity HTTP Contracts, Errors, and Secret-Safe Logging

**Files:**
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/controller/UserController.java`
- Delete: `src/main/java/au/com/dingwall/mark/bitbrush/dto/UserRegistrationRequest.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/service/PixelService.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/exception/UserNotFoundException.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/controller/PixelController.java`
- Test: `src/test/java/au/com/dingwall/mark/bitbrush/controller/UserControllerSliceTest.java`
- Modify: `src/test/java/au/com/dingwall/mark/bitbrush/controller/UserControllerTest.java`
- Modify: `src/test/java/au/com/dingwall/mark/bitbrush/service/PixelServiceTest.java`
- Modify: `src/test/java/au/com/dingwall/mark/bitbrush/exception/GlobalExceptionHandlerTest.java`
- Create: `src/test/java/au/com/dingwall/mark/bitbrush/SensitiveDataLoggingTest.java`

**Interfaces:**
- Consumes: Task 5 methods and Task 3 `ClientIpResolver`.
- Produces: POST `/api/users`, `/api/users/reconnect`, `/api/users/recover` exactly as specified; shared no-store responses; RFC 7807 mappings.

- [ ] **Step 1: Replace controller slice tests with all three contracts**

  Assert JSON request/response bodies, Unicode transport, service delegation, `201` create and `200` reconnect/recover, missing/malformed bodies, every validation error, source-IP delegation, and absence of `pin`, `pinHash`, and `pinConfirmation` response properties. Every successful identity response must include `Cache-Control: no-store`.

- [ ] **Step 2: Implement the thin controller**

  Inject only `UserIdentityService` and `ClientIpResolver`. Return `ResponseEntity<UserIdentityResponse>`; pass the Turnstile header to create/recover and resolve IP only for recovery. Do not log request DTOs or identity response values. In this same step delete `UserRegistrationRequest`, remove the now-unused `PixelService.userExists/registerUser` methods, and replace their old `PixelServiceTest` cases with identity-service coverage so no intermediate commit has dangling callers.

- [ ] **Step 3: Test and implement generic errors**

  Map duplicate identity to 409; invalid credentials to generic title `Unauthorized` and detail `Invalid username or PIN`; recovery throttle to 429 and computed `Retry-After`; map capacity to generic 429; PIN capacity to 503 and `Retry-After: 1`; invalid PIN to 400; retain Turnstile 403 and balance 402. Change `UserNotFoundException` to a constant detail so it never embeds the submitted credential.

- [ ] **Step 4: Add captured-log privacy tests and remove sensitive logging**

  At DEBUG/TRACE exercise create, reconnect, recover, failed reconnect, pixel placement, STOMP connect/disconnect, and exception rendering. Seed unique marker values for private UUID, PIN, encoded hash, and pepper, then assert none occurs in captured output or ProblemDetail JSON. Logs may contain session IDs, counts, and public author IDs only.

- [ ] **Step 5: Run HTTP/privacy tests and commit**

  Run: `./gradlew test --tests '*UserController*' --tests '*GlobalExceptionHandlerTest' --tests '*SensitiveDataLoggingTest'`

  Expected: PASS.

  ```bash
  git add src/main/java src/test/java
  git commit -m "feat: expose secure identity APIs"
  ```

### Task 7: Authenticated STOMP Lifecycle and Multi-Session Banking

**Files:**
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/config/WebSocketConfig.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/websocket/WebSocketEventListener.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/service/BankingService.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/service/TurnstileService.java`
- Modify: `src/test/java/au/com/dingwall/mark/bitbrush/service/BankingServiceTest.java`
- Modify: `src/test/java/au/com/dingwall/mark/bitbrush/websocket/WebSocketIntegrationTest.java`

**Interfaces:**
- Consumes: `UserRepository.existsById`, canonical UUID validation, `TurnstileService.markVerified`, and Spring `SimpUserRegistry`.
- Produces: authenticated principal for both `CONNECT` and `STOMP`; presence initialization on `SessionConnectedEvent`; one banking tick per unique registry user.

- [ ] **Step 1: Rewrite banking unit tests against `SimpUserRegistry` snapshots**

  Mock zero users, one session, two sessions for one user, multiple users, and disconnect-between-ticks. Assert one `compute`/increment per unique principal and one `convertAndSendToUser` fanout call, retained balance, no earning after registry removal, and no custom UUID/session map.

- [ ] **Step 2: Implement registry-backed banking**

  Inject `SimpUserRegistry`; remove `uuidToSessionId`, `onUserDisconnect`, and session arguments. Replace connect with `ensureBank(String principalName)`. Iterate `simpUserRegistry.getUsers()` once per tick, use each `SimpUser.getName()`, and allow `convertAndSendToUser` to fan out. `getInitialState` uses `computeIfAbsent` defensively.

- [ ] **Step 3: Add interceptor unit/integration cases for both connection commands**

  Cover known canonical private UUID, missing, blank, malformed, public author ID, unknown UUID, and repository failure for both `CONNECT` and `STOMP`. Assert rejected cases receive no CONNECTED frame and never call `markVerified`; accepted cases expose the expected Principal and call `markVerified` synchronously.

- [ ] **Step 4: Implement synchronous STOMP authentication**

  Extract the interceptor as a named bean/class for focused testing. For either connection command, call `CanonicalUuidValidator.isCanonical(uuid)`, perform `userRepository.existsById(uuid)`, then call `accessor.setUser(new StompPrincipal(uuid))` and `markVerified(uuid)`. Throw one generic `MessagingException("Invalid connection identity")` for every rejected path and never echo the header.

- [ ] **Step 5: Simplify lifecycle listener and verification lifetime**

  Read `event.getUser()` at `SessionConnectedEvent`, add the idempotent session ID to the existing online-count set, and call `bankingService.ensureBank(principal.getName())`. On disconnect remove only the session ID and broadcast count. Remove `removeVerified` use and method; verification persists until restart.

- [ ] **Step 6: Add raw-frame and deterministic multi-session integration tests**

  Send an invalid raw `STOMP` frame followed in the same socket write by `SEND`; assert no registry session, count change, bank, application invocation, or pixel broadcast. Add reconnect-after-cache-clear, blocked-downstream-after-validation, and disconnect-retains-verification cases. For two sessions: wait for both subscription receipts and registry state one user/two sessions, drain initial messages, call `earnPoints`, assert exactly one `balance + 1` message on each and no extra; disconnect one and await one session, tick and assert only survivor advances; disconnect final, await no user, tick and assert no earning. Set a long scheduler interval in this test.

- [ ] **Step 7: Run WebSocket/banking tests and commit**

  Run: `./gradlew test --tests '*BankingServiceTest' --tests '*WebSocketIntegrationTest'`

  Expected: PASS without timing sleeps used as correctness gates.

  ```bash
  git add src/main/java src/test/java
  git commit -m "feat: authenticate STOMP identities and support multiple sessions"
  ```

### Task 8: PostgreSQL Legacy Identity Migration

**Files:**
- Create: `src/main/resources/db/migration/V2__add_identity_credential_columns.sql`
- Create: `src/main/java/db/migration/V3__Backfill_user_pin_credentials.java`
- Create: `src/main/resources/db/migration/V4__enforce_identity_constraints.sql`
- Modify: `build.gradle.kts`
- Create: `src/test/java/au/com/dingwall/mark/bitbrush/migration/LegacyIdentityMigrationTest.java`

**Interfaces:**
- Consumes: Task 1 codec via Flyway placeholder `pin-pepper`; Task 4 repository/entity.
- Produces: final production schema with rotated legacy private UUIDs and reproducible hashed legacy PINs.

- [ ] **Step 1: Add PostgreSQL Testcontainers and a failing V1-to-final migration test**

  Add the Testcontainers BOM plus JUnit Jupiter and PostgreSQL modules. Start PostgreSQL, migrate only V1, insert multiple legacy users/pixels, then run all migrations with a test Base64 pepper passed as Flyway placeholder. Assert old UUID is `author_id`, private UUID rotated canonically, pixels are not rewritten, hashes verify, `pin_backfilled=true`, constraints reject null/duplicates, and Hibernate `validate` succeeds.

- [ ] **Step 2: Add migration SQL shells and implement Java backfill**

  V2 renames `pixels.author_uuid` to `author_id` and adds nullable user columns. V3 iterates users in stable order using JDBC, assigns old UUID to author ID, chooses random canonical UUID absent from both ID columns, derives four digits from stable author ID, hashes via the same `PinCredentialCodec`, and updates with `pin_backfilled=true`. V4 sets defaults for new `pin_backfilled=false`, adds `NOT NULL`, and names `pk_users_uuid`, `uk_users_username`, and `uk_users_author_id`. Do no filesystem I/O in migrations.

- [ ] **Step 3: Extend migration tests for retry/idempotence and adversarial bearer paths**

  Force UUID collisions, verify legacy PIN derivation stability/domain/pepper changes and lack of modulo bias via controlled digests, run Flyway again without changing hashes, and cover an empty V1 database. Start the migrated application and prove the old UUID-shaped public ID fails reconnect, placement, `CONNECT`, and `STOMP`, while recovery returns the rotated UUID and that value succeeds through every path.

- [ ] **Step 4: Add CI migration enforcement and run the migration test**

  Add a `migrationTest` Gradle task including only `**/LegacyIdentityMigrationTest.class`, while the ordinary `test` task explicitly excludes that class. Keep `useJUnitPlatform()` on all `Test` tasks, but move `finalizedBy(jacocoTestReport)` from `tasks.withType<Test>` to `tasks.named<Test>("test")`; retain `jacocoTestReport.dependsOn(test)`. This prevents `migrationTest` from pulling in the ordinary suite and prevents the container test from running twice. Make the test use a Docker availability assumption locally but fail under `CI=true` if unavailable/skipped. Add a distinct CI step `./gradlew migrationTest --no-daemon` before the normal build.

  Run with Docker: `./gradlew migrationTest`

  Expected: PASS; migration task executes rather than skips when Docker is available.

- [ ] **Step 5: Commit**

  ```bash
  git add build.gradle.kts .github/workflows/ci.yml src/main/java src/main/resources/db src/test/java
  git commit -m "feat: migrate legacy user identities"
  ```

### Task 9: Protected Legacy PIN Export

**Files:**
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/service/SecureExportPublisher.java`
- Create: `src/main/java/au/com/dingwall/mark/bitbrush/service/LegacyPinExportRunner.java`
- Modify: `src/main/java/au/com/dingwall/mark/bitbrush/config/PinProperties.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-test.properties`
- Modify: `src/main/resources/application-docker.properties`
- Modify: `src/main/resources/application-prod.properties`
- Create: `src/test/java/au/com/dingwall/mark/bitbrush/service/SecureExportPublisherTest.java`
- Create: `src/test/java/au/com/dingwall/mark/bitbrush/service/LegacyPinExportRunnerTest.java`

**Interfaces:**
- Consumes: `UserRepository.findAllByPinBackfilledTrueOrderByUsernameAsc()` and `PinCredentialService.deriveLegacyPin(authorId)`.
- Produces: startup-only export enabled by `PIN_BACKFILL_EXPORT_PATH`; secure deterministic `username<TAB>PIN\n` bytes.

- [ ] **Step 1: Write exhaustive secure publisher tests**

  On POSIX hosts assert creation of an absent private directory at `0700`, temporary and target `0600`, fsync-before-publication, hard-link no-replace publication, deterministic publish race, exact-content restart acceptance, and rejection of mismatched target, symlink, wrong modes/owner, non-directory parent, unwritable path, and filesystems lacking required primitives. Assert failure deletes only the invocation's recognizable temporary; retry removes stale recognizable temporaries but leaves unrelated files.

- [ ] **Step 2: Implement `SecureExportPublisher`**

  Expose `void publish(Path target, byte[] expectedContent)`. Require an absolute target whose direct parent is the dedicated directory. Validate with `NOFOLLOW_LINKS` and Unix attributes, create temp with `CREATE_NEW`, write and `FileChannel.force(true)`, publish with `Files.createLink(target,temp)` so an existing target wins, fsync the directory, then unlink temp. Never replace target. Existing target is accepted only when owned by the current effective user, regular/non-symlink, `0600`, and byte-identical.

- [ ] **Step 3: Write runner tests**

  Assert blank path and zero backfilled rows perform no filesystem work; rows are sorted and represented once; tab/CR/LF usernames fail; deletion followed by rerun reproduces identical bytes; a valid existing export is accepted; publisher failure aborts startup without database mutation; logs contain only path, row count, and machine ID and contain no PIN/pepper/hash.

- [ ] **Step 4: Implement the post-migration runner**

  Add `String backfillExportPath` to `PinProperties`, bound from `${PIN_BACKFILL_EXPORT_PATH:}`. When blank, do nothing; otherwise convert it with `Path.of` and require an absolute path. Query only backfilled users sorted by username; if none, do not create a file. Reject tab/CR/LF defensively, derive each PIN from `authorId`, build UTF-8 TSV, and publish. Log only path, row count, and `${FLY_MACHINE_ID:local}`; fail startup on unsafe publication.

- [ ] **Step 5: Run focused tests and commit**

  Run: `./gradlew test --tests '*SecureExportPublisherTest' --tests '*LegacyPinExportRunnerTest'`

  Expected: PASS.

  ```bash
  git add src/main/java src/main/resources/application-*.properties src/test/java
  git commit -m "feat: securely export legacy recovery PINs"
  ```

### Task 10: Deterministic Local Browser Harness

**Files:**
- Create: `e2e/playwright.local.config.ts`
- Create: `e2e/local-server.mjs`
- Create: `e2e/fixtures/widget-host.html`
- Create: `e2e/tests/local-harness.spec.ts`
- Modify: `e2e/package.json`

**Interfaces:**
- Consumes: current static resources and external script/API URLs.
- Produces: `npm run test:local`; a localhost server; deterministic SockJS/STOMP/Turnstile/API fakes available to later browser tasks.

- [ ] **Step 1: Write a failing harness smoke test**

  Open `/index.html` and `/widget-host.html`, assert each static client loads, and assert intercepted Turnstile and STOMP fakes record calls without contacting Cloudflare, CDNs, or BitBrush production. The fake STOMP client records constructor arguments, `activate()` time, CONNECT headers, subscriptions, and injected messages in `window.__bitbrushTest`.

- [ ] **Step 2: Implement the local server and routing fixtures**

  Use Node's built-in `http`, `fs`, and `path` modules—no runtime package. Serve only `src/main/resources/static` and `e2e/fixtures`, reject path traversal, and select an explicit localhost port in the Playwright config. Route external scripts to deterministic JavaScript bodies and route identity/canvas requests from each test without logging bodies.

- [ ] **Step 3: Add the local command and run it**

  Add `"test:local": "playwright test --config=playwright.local.config.ts"` and a Playwright `webServer` entry running `node local-server.mjs`. Set local `testMatch` to `['**/local-harness.spec.ts', '**/identity-full-page.spec.ts', '**/identity-widget.spec.ts']`; set the existing production config's `testMatch` to `'**/bitbrush-widget.spec.ts'`. These complementary selectors keep local and production discovery disjoint even though the files share `e2e/tests`.

  Run: `cd e2e && npm ci && npx playwright install chromium && npm run test:local`

  Expected: PASS without runtime network calls after npm/browser installation.

- [ ] **Step 4: Commit**

  ```bash
  git add e2e
  git commit -m "test: add local browser harness"
  ```

### Task 11: Full-Page Create, Reconnect, and Recovery UI

**Files:**
- Modify: `src/main/resources/static/index.html`
- Create: `e2e/check-index-syntax.mjs`
- Create: `e2e/tests/identity-full-page.spec.ts`

**Interfaces:**
- Consumes: Task 6 JSON contracts, Task 10 browser fakes, and public `authorId` payloads.
- Produces: `initIdentity(): Promise<UserIdentity>` that settles before `connectWebSocket(identity.uuid)`.

- [ ] **Step 1: Write failing full-page browser tests**

  Cover create/recover switching, opaque `A😀b!` submission, server confirmation error rendering, recovery failure clearing PIN, success storing exactly UUID/username, UUID-only reconnect, stale UUID 404 clearing both, username-only cleanup, Turnstile refresh, public `authorId` cache invalidation, and absence of PIN in storage. Delay identity responses and assert no STOMP construction/activation until resolution; afterward assert the authoritative UUID CONNECT header.

- [ ] **Step 2: Define modal markup and implement identity resolution**

  Add username, password-masked PIN, confirm PIN, primary CTA, mode toggle, and error region. Use `autocomplete="new-password"` for create PINs and `autocomplete="current-password"` for recovery; do not add PIN `maxlength`. Replace eager UUID creation with:

  ```javascript
  function persistIdentity(identity) {
    localStorage.setItem('bitbrush_uuid', identity.uuid);
    localStorage.setItem('bitbrush_username', identity.username);
  }
  function clearIdentity() {
    localStorage.removeItem('bitbrush_uuid');
    localStorage.removeItem('bitbrush_username');
  }
  initIdentity().then((identity) => connectWebSocket(identity.uuid));
  ```

  Reconnect posts only `{uuid}` even without stored username and overwrites username from the response. A 404 clears both and opens the modal; username without UUID is discarded. Create generates a temporary UUID only on submission; recovery uses the returned UUID.

- [ ] **Step 3: Implement mode submissions and public author state**

  Send exact `input.value` PIN strings, reuse Turnstile helpers, persist only successful response identity, clear PIN fields on every close/failure, and render server validation. Replace canvas response/broadcast cache reads from `authorUuid` to `authorId`; retain private UUID only in outbound placement.

- [ ] **Step 4: Run behavior and syntax tests, then commit**

  Make `e2e/check-index-syntax.mjs` compile each non-empty inline script with `new Function(source)`.

  Run: `node e2e/check-index-syntax.mjs`

  Run: `cd e2e && npm run test:local -- identity-full-page.spec.ts`

  Expected: PASS.

  ```bash
  git add src/main/resources/static/index.html e2e
  git commit -m "feat: add PIN recovery to full-page client"
  ```

### Task 12: Standalone Widget Identity UI and Startup Ordering

**Files:**
- Modify: `src/main/resources/static/bitbrush-widget.js`
- Create: `e2e/tests/identity-widget.spec.ts`

**Interfaces:**
- Consumes: Task 11 behavior with `SERVER` prefix and `bitbrush_widget_` keys, plus Task 10 browser fakes.
- Produces: independently embeddable widget whose WebSocket is constructed only after authoritative identity resolution.

- [ ] **Step 1: Write failing widget browser tests**

  Repeat the full identity/storage/public-author matrix for the widget-prefixed keys and server-prefixed URLs. Explicitly delay recovery/reconnect and prove the current connect-before-identity order is gone.

- [ ] **Step 2: Implement the mirrored identity modal and ordering**

  Preserve dependency-free DOM/CSS creation and `bbw-` names. Apply Task 11 semantics, then order startup exactly:

  ```javascript
  Promise.all([sockjsPromise, stompPromise, turnstilePromise])
    .then(function () { return initIdentity(); })
    .then(function (identity) { connectWebSocket(identity.uuid); });
  ```

  Use masked PIN inputs and correct autocomplete without PIN maxlength. Replace public cache reads with `authorId`, retaining `authorUuid` only for outbound placement.

- [ ] **Step 3: Run behavior and syntax tests, then commit**

  Run: `node --check src/main/resources/static/bitbrush-widget.js`

  Run: `cd e2e && npm run test:local -- identity-widget.spec.ts`

  Expected: PASS.

  ```bash
  git add src/main/resources/static/bitbrush-widget.js e2e/tests/identity-widget.spec.ts
  git commit -m "feat: add PIN recovery to embeddable widget"
  ```

### Task 13: Production Smoke Identity Provisioning

**Files:**
- Modify: `e2e/tests/bitbrush-widget.spec.ts`
- Modify: `e2e/package.json`
- Modify: `e2e/playwright.config.ts`

**Interfaces:**
- Consumes: provisioned `BITBRUSH_E2E_UUID` and deployed `/api/users/reconnect`.
- Produces: `npm run test:production` with no credential committed to source control.

- [ ] **Step 1: Replace production random registration**

  Read `process.env.BITBRUSH_E2E_UUID` and throw before browser navigation when absent. Seed only that UUID; let `/api/users/reconnect` populate the authoritative widget username. Never embed a PIN or reusable UUID in fixtures, screenshots, traces, or logs.

- [ ] **Step 2: Add the explicit production command and verify fail-fast locally**

  Add `"test:production": "playwright test --config=playwright.config.ts"`. Run `cd e2e && npm run test:production` without the secret and assert it exits before navigation with `BITBRUSH_E2E_UUID is required`.

- [ ] **Step 3: Commit**

  ```bash
  git add e2e
  git commit -m "test: provision production smoke identity"
  ```

### Task 14: End-to-End Integration, Coverage Audit, Documentation, and Rollout Checks

**Files:**
- Create: `src/test/java/au/com/dingwall/mark/bitbrush/UserIdentityIntegrationTest.java`
- Create: `src/test/java/au/com/dingwall/mark/bitbrush/service/PinCredentialCalibrationTest.java`
- Modify: all existing tests that create users
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docker-compose.yml`
- Modify: `fly.toml`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: all prior task interfaces.
- Produces: verified whole-feature workflows, deployment/operator instructions, and CI gates.

- [ ] **Step 1: Add full Spring identity sequences**

  With real repositories and cheap test Argon2 parameters, execute create→reconnect and create→recover→place-pixel. Assert Turnstile/verification ordering, authoritative username, same recovered private UUID, retained in-memory bank within process lifetime, and public-only pixel info/broadcasts. Add concurrent creates for duplicate UUID and username and assert one success plus one 409.

- [ ] **Step 2: Deliberately update every old user fixture**

  Search with `rg 'new User|/api/users|registerUser|UserRegistrationRequest' src/test`. Creation-contract tests must provide PIN/confirmation; tests unrelated to creation must use a fixture helper that persists complete `User` rows. Do not retain compatibility branches accepting missing PINs.

- [ ] **Step 3: Calibrate production Argon2 settings within the 512 MiB limit**

  Add an opt-in test enabled by `PIN_CALIBRATION=true`. It constructs the production `19456/2/1/32` codec with a generated test pepper, warms it once, measures ten sequential hashes/verifications, then starts two simultaneous hashes behind a barrier. Assert both complete, hashes verify, and peak overlap is two. Run the test in a Gradle container constrained to 512 MiB; write timings only to `build/reports/pin-calibration.txt`, never PIN/keyed material/hash/pepper:

  `docker run --rm --memory=512m -e PIN_CALIBRATION=true -v /home/mark/kramtime/BitBrush/.worktrees/username-pin-recovery:/workspace -w /workspace gradle:8.14.4-jdk21 ./gradlew test --tests '*PinCredentialCalibrationTest' --no-daemon`

  Record the measured median and maximum in the deployment documentation. Increase memory/iterations only if the 512 MiB concurrent run remains healthy and latency stays operationally acceptable; never reduce the global minima.

- [ ] **Step 4: Run the full suite and inspect meaningful uncovered branches**

  Run: `./gradlew build`

  Expected: PASS. Inspect `build/reports/jacoco/test/jacocoTestReport.csv` for new credential, throttle, filter, identity, migration-support, exporter, WebSocket, and banking classes. Add focused tests for any missed failure branch involving secrets, concurrency, constraints, filesystem safety, or authorization; do not chase trivial record/accessor coverage.

- [ ] **Step 5: Update architecture and operator documentation**

  Document three endpoints, local storage containing UUID/username only, public author IDs, process-local throttle assumption, `PIN_PEPPER` generation/backup and loss consequences, Argon2 calibration values, `PIN_BACKFILL_EXPORT_PATH`, protected retrieval/deletion order, `BITBRUSH_E2E_UUID`, immediate deployment strategy, PostgreSQL backup, and roll-forward-only corrective rollback after V4. Update Docker/Fly configuration to require the pepper without committing one.

- [ ] **Step 6: Build the production container and run local browser checks**

  Run: `docker build -t bitbrush:pin-recovery .`

  Run: `cd e2e && npm run test:local`

  Expected: image builds and local browser suite passes.

- [ ] **Step 7: Run a secret-output audit**

  Run: `rg -n 'pin|pepper|uuid|authorUuid' src/main src/test README.md CLAUDE.md e2e`

  Review each match: PIN/pepper/private UUID may occur as variable/property names and inbound DTO fields, but no logging call, public response DTO, committed secret value, URL query, local-storage PIN key, or test output may contain their values. Verify all identity responses set no-store.

- [ ] **Step 8: Commit the integrated feature**

  ```bash
  git add src/test README.md CLAUDE.md docker-compose.yml fly.toml .github/workflows/ci.yml
  git commit -m "docs: document PIN recovery rollout"
  ```

## Final Verification

- [ ] Run `./gradlew clean build` and confirm all unit, slice, repository, integration, and WebSocket tests pass.
- [ ] Run `./gradlew migrationTest` with Docker and confirm it executes without a skip.
- [ ] Run `docker build -t bitbrush:pin-recovery .`.
- [ ] Run `cd e2e && npm run test:local`.
- [ ] Inspect JaCoCo for meaningful missed security/concurrency branches.
- [ ] Run the secret-output audit and review every match.
- [ ] Use `superpowers:requesting-code-review` for the whole branch, explicitly supplying this plan, the design spec, deferred-minor ledger entries, and the complete branch diff.
- [ ] Use `superpowers:verification-before-completion` before reporting success.
- [ ] Use `superpowers:finishing-a-development-branch` to present integration choices.
