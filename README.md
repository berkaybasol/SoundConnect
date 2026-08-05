# SoundConnect Backend

Türkçe günlük çalıştırma, IntelliJ/Android Studio ve PgAdmin rehberi için [`CALISTIRMA_REHBERI.md`](CALISTIRMA_REHBERI.md) dosyasını kullan.

SoundConnect Backend is a Java 21 / Spring Boot modular monolith. It exposes REST and STOMP APIs for authentication, profiles, media, direct messaging, notifications, table groups, events, setlists, engagement, and related music-platform workflows.

## Runtime dependencies

- Java 21
- PostgreSQL
- Redis
- RabbitMQ with the `rabbitmq_delayed_message_exchange` plugin
- S3-compatible object storage with separate public-CDN and private-origin buckets (AWS S3 in production)
- `ffmpeg` and `ffprobe` on `PATH` only for native IntelliJ development; the
  dedicated worker image provides them in container deployments
- MailerSend, Google Identity, and Spotify credentials

RabbitMQ must expose the delayed-message plugin before the application declares `x-delayed-message` exchanges. The standard RabbitMQ image does not bundle that community plugin; use an internally maintained broker image/version that includes it, and verify it with:

```bash
rabbitmq-plugins list -e
```

## Local setup (recommended)

Docker Compose provides the API, isolated native media worker, PostgreSQL,
Redis, and a RabbitMQ image with the delayed-message plugin already installed.
Database and broker data live in named Docker volumes, so normal restarts and
`down`/`up` cycles do not erase them.

The first command creates `.env.local` from the committed template and stops.
Fill in the real API secrets once, then run it again. `dev.cmd` also creates
independent 256-bit PostgreSQL and RabbitMQ credentials for the media worker in
ignored `.env.worker-db.local` and `.env.worker-rabbit.local` files; those
credentials are never injected into the API process.

```powershell
.\dev.cmd up
```

`up` waits for the complete Hibernate-managed API startup, briefly quiesces the
API and media worker, and applies the idempotent Studio PostgreSQL
reconciliation before reopening the complete stack. This keeps existing local
volumes aligned with the Studio constraints and indexes without requiring a
destructive reset after every schema change.

Useful commands:

```powershell
# Start only PostgreSQL, RabbitMQ, and Redis
.\dev.cmd infra

# Start infrastructure, stop the Compose backend, load .env.local,
# and run Spring Boot natively
.\dev.cmd boot

# Follow all logs, or only one service
.\dev.cmd logs
.\dev.cmd logs -Service backend

# Inspect or stop the stack
.\dev.cmd ps
.\dev.cmd down

# Validate interpolation and Compose syntax
.\dev.cmd config
```

`up` is the most reproducible option and needs only Docker on a new machine. `boot` is convenient while actively changing Java code because Gradle runs on the host. The local API listens on port `8080`; PostgreSQL is exposed on `localhost:5433` to avoid colliding with a host installation, RabbitMQ on `localhost:5673` with management UI on `localhost:15672`, and Redis on `localhost:6379`. Infrastructure ports bind only to the loopback interface and are not reachable from the LAN.

Do not run `docker compose down --volumes` unless local PostgreSQL, RabbitMQ, and Redis data should be permanently deleted.

### Native setup without Compose

Start the external services yourself, export the values from `.env.local` through the shell or IDE, ensure `ffmpeg` and `ffprobe` are on `PATH`, then start the API:

```powershell
.\gradlew.bat bootRun
```

Linux/macOS:

```bash
./gradlew bootRun
```

The API listens on port `8080` by default. Local OpenAPI UI is available at `/swagger-ui.html`, and health is available at `/actuator/health`.

## Configuration

All secrets and environment-specific addresses are injected at runtime. See [`.env.example`](.env.example) for the API baseline and the two `.env.worker-*.example` files for the isolated worker credential contracts. Every `.local` env file is deliberately ignored by Git and must never be committed.

For a new API setting, add the key to `.env.example` with a safe example and to your `.env.local` with the real local value. Never add a `SOUNDCONNECT_MEDIA_WORKER_*` key to `.env.local`; the development preflight rejects that boundary violation. Production secrets belong in the deployment platform's secret manager, not in local files.

The legacy `SOUNDCONNECT_POSTGRE_*` database variables remain supported for existing developer machines, but new environments should use `SOUNDCONNECT_POSTGRES_*`.

For S3 credentials, leave `S3_ACCESS_KEY` and `S3_SECRET_KEY` empty to use the AWS SDK default credentials provider chain. IAM roles/workload identity are recommended in production. If static credentials are used locally, both values must be configured together. `S3_PRIVATE_BUCKET` is mandatory in production, must differ from `S3_BUCKET`, and must not be attached to the public CDN distribution. Configure a private-bucket lifecycle rule that expires `quarantine/` within 24 hours and aborts incomplete multipart uploads. Cross-bucket copy requires source `s3:GetObject` and destination `s3:PutObject` (plus both KMS keys when applicable); browser PUT CORS is required only on the private origin.

## Build and test

```powershell
.\gradlew.bat clean test
.\gradlew.bat bootJar mediaWorkerBootJar
```

Linux/macOS:

```bash
./gradlew clean test
./gradlew bootJar mediaWorkerBootJar
```

Test reports are written to `build/reports/tests/test/index.html`. CI executes a clean Java 21 test run for every pull request and for pushes to `main`.

## Production profile

Run with `SPRING_PROFILES_ACTIVE=prod`. The production profile sets the following safe defaults:

- Hibernate schema mode is `validate`; it never mutates the database.
- location and owner bootstrap initializers are disabled;
- Swagger/OpenAPI endpoints are disabled;
- framework and application debug logging is disabled;
- error responses never expose exception messages, binding objects, or stack traces;
- RabbitMQ, Redis, CORS, MailerSend, both S3 buckets, and AWS region must be supplied by the deployment environment.

At startup, the production safety validator checks the effective configuration after all environment overrides are applied. The application refuses to start if schema mutation, bootstrap/seed jobs, Swagger, or detailed framework error output has been enabled.

The production database schema must be deployed before the application starts. Until versioned migrations are introduced, schema provisioning is an explicit release prerequisite. The required baseline, backfills, rollout order, and unresolved product decisions are tracked in [`docs/ReleaseReadiness.md`](docs/ReleaseReadiness.md); staging/production deployment is blocked until that gate is completed against the real PostgreSQL and object-storage state.

## Container images

The multi-stage `Dockerfile` has two deliberate runtime targets:

```bash
docker build --target api -t soundconnect-api:local .
docker build --target media-worker -t soundconnect-media-worker:local .
```

The unprivileged API target contains no FFmpeg and exposes port 8080. The
unprivileged media-worker target is the only image with FFmpeg/ffprobe; it has
no HTTP port and starts the explicit no-web worker context. Never deploy the
API artifact with worker profiles or put API JWT/OAuth/mail credentials in the
worker environment. Exact database, RabbitMQ, S3/IAM, network-policy, health,
and rollout contracts are documented in
[`docs/MediaModule/IsolatedMediaWorker.md`](docs/MediaModule/IsolatedMediaWorker.md).

Both images default to their safe production profiles. Never reuse the local
`.env` file for deployment. Keep separately managed API and worker production
secret sets. Environment variables intentionally have higher precedence than
profile configuration, and unsafe overrides are rejected by each process's
startup guard. PostgreSQL, Redis, RabbitMQ, and object storage remain external
services.

## Operational notes

- Use `/actuator/health/liveness` for restart decisions and `/actuator/health/readiness` for traffic admission. The image health check uses liveness so a transient database or broker outage does not trigger a restart loop. Health details are not exposed publicly.
- Do not commit `.env` files, cloud keys, JWT secrets, OAuth secrets, or database credentials.
- Keep REST DTOs, error codes, STOMP destinations, and the Flutter client contract synchronized in the same release.
- API failures use the stable `ErrorResponse` envelope (`code`, `message`, `httpStatus`, `path`, `details`, `timestamp`). Error codes are unique; authentication, authorization, validation, conflict, not-found, method, and media-type failures retain their native HTTP semantics.
- Media upload follows `init -> private mutable signed PUT -> ETag-conditional immutable snapshot -> validate -> publish/queue`. Untrusted or client-mutable bytes never enter the CDN bucket or the transcode worker. Public image/audio is server-side promoted with transaction compensation; verified raw public-video sources remain private and only generated HLS output is public. `PRIVATE` and `UNLISTED` progressive objects use immutable keys in the separate private-origin bucket and receive only owner-authorized, short-lived GET URLs; those URLs are never persisted. Protected HLS video is rejected fail-closed until signed manifest-and-segment delivery is implemented.
- Authentication rate limiting applies two independent counters: the trusted client-address dimension and a SHA-256 account-identity dimension. Raw usernames, emails, and Google subjects are never written to Redis or logs. Client identity uses the socket peer by default. Only the single header selected by `AUTH_RATE_LIMIT_FORWARDED_HEADER` (`FORWARDED` or `X_FORWARDED_FOR`) is considered, and only when that peer is inside `AUTH_RATE_LIMIT_TRUSTED_PROXY_CIDRS`; repeated fields are combined and the chain is evaluated from the trusted edge inward. Keep the CIDR list empty for direct exposure. A trusted ingress must remove every client-supplied forwarding header, build/append the selected canonical chain itself, and drop the unselected forwarding header; CIDR trust alone cannot compensate for an ingress that preserves spoofed headers.
- Media upload initialization uses one atomic Redis decision per authenticated user for request count, declared bytes, and concurrent `UPLOADING` reservations. Quota/cap failures return stable HTTP 429 media error codes with `Retry-After`. Redis failure is fail-closed for new signed uploads by default, while media reads remain available.
- Upload reservations expire automatically. Abandoned or integrity-rejected `UPLOADING` rows are atomically moved to `CLEANUP_PENDING`; object deletion is idempotently retried before the row becomes `FAILED`, so transient storage failures do not silently orphan objects. The durable intent survives the exact presign expiry plus a minimum 24-hour in-flight request completion fence because S3 validates expiry when a PUT starts. Failed HLS work is cleaned by a bounded retry worker, never synchronously on a Rabbit listener. Provider lifecycle expiration for `quarantine/` and incomplete-multipart abort remain mandatory defense in depth.
- JPA audit timestamps, JDBC timestamp conversion, JSON date handling, database
  sessions, and the container JVM use UTC. The production safety validator
  rejects an override of the JDBC session timezone. Convert to the user's local
  timezone only at the client boundary.
- OTP Redis keys contain a SHA-256 identity digest instead of a raw email address, and related code/cooldown keys share a Redis Cluster hash tag so Lua operations stay in one slot. Resend always uses an isolated decoy claim to drive the public status/TTL/cooldown contract; account existence, verification state, internal OTP cooldown, and mail-provider outcome are not disclosed. The first deployment of these namespaces invalidates already-active legacy OTPs, so affected users must request a fresh code; legacy entries expire naturally within their configured OTP TTL/cooldown and require no manual cleanup.
- Google accounts are bound to the verified OpenID Connect `sub`, never to mutable email alone. Before deploying this code with `ddl-auto=validate`, normalize existing user emails, resolve case-insensitive collisions, add nullable `tbl_user.provider_subject varchar(255)`, and add unique indexes for canonical email and `(provider, provider_subject)`. Existing legacy Google rows can remain null and are bound under a row lock on their next successful Google login; local accounts are never auto-linked by email.
- Open Session in View remains enabled temporarily because several legacy response mappers traverse lazy associations after service methods return. Before disabling it, move those reads into explicit read-only transactions and define fetch joins, entity graphs, or DTO projections per endpoint; otherwise production requests can fail with lazy-initialization errors. Treat this as a release prerequisite for the persistence-query refactor and monitor serialization-time query counts until it is completed.

Module-specific notes live under [`docs/`](docs/), including RabbitMQ, Redis, WebSocket/STOMP, mail, and media/transcode documentation.
