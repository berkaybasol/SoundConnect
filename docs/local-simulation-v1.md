# SoundConnect Local Simulation V1

## Purpose

Build a repeatable, local-only SoundConnect world that is rich enough to exercise the musician feed and the existing modules that feed it. The simulation must use production business rules and application services wherever possible; it must not fabricate feed responses or require real email addresses.

## Product decisions

- The first supported feed perspective is Musician.
- The simulated world contains 50 accounts:
  - 24 musicians
  - 10 listeners
  - 8 venues
  - 8 studios
- Organizer and producer accounts are not created.
- Four band profiles are managed by simulated musicians and do not add to the account count.
- Only approved venue accounts create events.
- Listener Overthinking and TableGroup content reaches the musician feed only through eligible listener profile publications and at a lower frequency than core feed items.
- Ghost listeners do not publish, cannot be followed, and never appear as feed actors or publishers.
- Pending, rejected, inactive, unverified, erased, draft, closed, deleted, and performer-pending records are retained as negative controls where the production rules support them.
- Existing local data is disposable. A fresh simulation is allowed to reset the local application data before rebuilding the world.
- The world uses real catalog-backed Turkish locations and fictional, persistent people, bands, venues, and studios.
- Test identities use reserved non-deliverable email addresses. No real OTP, application-decision, notification, payment, or sponsorship message may leave the local environment.
- V1 uses the existing development musician-feed sponsor fixture. The production promotion-to-feed integration remains separate sponsorship work.

## World layout

| Scene | Musicians | Listeners | Venues | Studios | Bands |
| --- | ---: | ---: | ---: | ---: | ---: |
| Istanbul alternative / rock | 10 | 3 | 3 | 3 | 2 |
| Ankara pop / electronic | 5 | 2 | 1 | 2 | 1 |
| Izmir jazz / funk | 4 | 2 | 2 | 1 | 1 |
| Bursa metal / acoustic | 3 | 1 | 1 | 1 | 0 |
| Antalya / Eskisehir bridges | 2 | 2 | 1 | 1 | 0 |

Three active musicians are reserved for manual observation:

1. complete profile with a mature follow graph;
2. incomplete profile that exercises profile-completion guidance;
3. cold-start profile with no follows or behavior history.

The behavior engine never acts as a reserved observer while it may still direct eligible activity toward that observer.

## Baseline content target

- 36 Collab listings: active, closed, and invisible drafts.
- 30 venue-created events: visible future events plus past, deleted, and performer-pending controls.
- 34 tracks owned only by currently supported musician, band, and studio owners.
- 60 profile media publications across supported public profile owners.
- 18 eligible listener Overthinking profile shares.
- 14 eligible listener TableGroup profile shares.
- 10 listener event profile publications.
- Approximately 240 follows, 300 likes, and 100 comments.

Exact counts may be lowered only when a production rule makes a requested combination invalid. The run report must state planned, created, skipped, and failed counts.

## Architecture

### Simulation runtime

- Activated only when both the `local` and `simulation` Spring profiles are active.
- Additionally gated by a default-false property and an explicit destructive-reset acknowledgement.
- Requires the disposable PostgreSQL database name to be supplied explicitly; it has no runnable default.
- Refuses to start when a production profile is active, the datasource is not PostgreSQL on a loopback host, the database name is not the configured local database, or the account cap exceeds 50.
- Uses Spring-proxied application services so transaction, authorization-independent domain validation, locking, versioning, and outbox behavior remain intact.
- Validates controller DTOs explicitly before invoking services because controller Bean Validation is otherwise bypassed.
- Never exposes a production-capable force-approve, force-ready, token-minting, or arbitrary SQL HTTP endpoint.

### Mail and notification containment

- A simulation-only primary `OtpMailService` captures OTP values in memory and never calls MailerSend.
- Registration verification still claims the real Redis OTP and executes the normal verification service.
- Venue/studio admin notification transports and decision emails are replaced with simulation-only no-send adapters while domain records and outbox transitions remain observable.
- Startup assertions prevent a no-send/capture adapter from loading outside the guarded simulation runtime.

### Determinism

- Every run has a stable run identifier and random seed.
- The canonical world/persona manifest is version controlled.
- A repeated fresh run with the same manifest and seed creates the same identities, relationships, story arcs, and action ordering.
- Prepared text and media manifests keep baseline creation independent of a live LLM service.

### Media

- Profile media and tracks follow the real init-upload, upload, complete, verification, worker, READY, and attach lifecycle.
- The runner waits for terminal media state and records timeout/failure diagnostics.
- Fixtures are copyright-safe and version controlled or generated specifically for the simulation.

### Behavior runtime

- Supports `fresh`, `resume`, `pause`, and accelerated `fast-forward` modes.
- Population agents use bounded persona rules and memory. They cannot run SQL or invoke actions outside an allowlist.
- Reserved observer accounts are locked from autonomous actions.
- Runtime LLM assistance is optional and must have a deterministic prepared-content fallback.

### Reporting

Each run produces a machine-readable result and a human-readable report containing:

- action timeline and actor identity;
- planned, succeeded, skipped, and failed totals;
- endpoint/service error codes and correlation identifiers;
- feed composition, duplicate/cursor checks, sponsor spacing, and latency;
- visibility and authorization invariant violations;
- Flutter diagnostic events and screenshots when UI automation is enabled.

Deterministic invariant failures and subjective AI UX observations are reported separately.

## Delivery slices

1. Guarded simulation configuration, OTP capture, outbound-message containment, and tests.
2. Canonical world manifest, registration/verification/admin approval, profiles, bands, and follow graph.
3. Collab, event, listener publication, engagement, and media/track story arcs.
4. Live population-agent scheduler, control surface, and reports.
5. Flutter debug persona selection plus deterministic device E2E tests.
6. Optional AI-guided UI exploration and UX report summarization.

No slice is considered complete without automated tests for its production-safety boundary and its failure behavior.

## Running from IntelliJ and Android Studio

Keep PostgreSQL, Redis, and RabbitMQ running, but leave the Compose API and media
worker stopped. No extra backend terminal process is required.

For the first rebuild, add these values to the ignored backend `.env.local`
file, which Spring imports automatically. An IntelliJ environment override is
also supported, but is not required. Then use the normal green Run button:

```text
SPRING_PROFILES_ACTIVE=local,simulation
SOUNDCONNECT_SIMULATION_ENABLED=true
SOUNDCONNECT_SIMULATION_MODE=FRESH
SOUNDCONNECT_SIMULATION_DESTRUCTIVE_RESET_ACKNOWLEDGED=true
SOUNDCONNECT_SIMULATION_COMMON_PASSWORD=<a local-only password of at least 12 characters>
SOUNDCONNECT_SIMULATION_EXPECTED_DATABASE=<the exact database name in SOUNDCONNECT_POSTGRES_URL>
SOUNDCONNECT_SIMULATION_MEDIA_PUBLIC_BASE_URL=http://10.0.2.2:8080
```

On the current local database this expected name is `soundconnectdb`; do not
copy that name to a different database blindly. `FRESH` deletes the disposable
local user-owned graph and rebuilds it. The
runtime refuses non-loopback PostgreSQL, a database-name mismatch, a `prod`
profile, a missing acknowledgement, or more than 50 manifest accounts before
the destructive step.

Wait for the log line `SoundConnect local simulation is READY`. The same state
is available without credentials at `GET /api/v1/public/simulation-status`.
It reports the exact mode plus `BOOTSTRAPPING`, `READY`, `PAUSED`, or `FAILED`
and the current phase, so the active mode is never implicit. A fatal bootstrap
failure is also written to the log and report before the fail-closed process
exits; in that case the HTTP `FAILED` state may only be visible briefly during
shutdown.

After the successful first run, change the mode to `RESUME` and set the reset
acknowledgement to `false`. `RESUME` verifies the stored world and continues
without recreating completed stories. `FAST_FORWARD` requires that same world
and executes the configured bounded action window. `PAUSE` performs no world
mutation and does not require the common password.

For Flutter, create the ignored `SoundConnect-Frontend/.local-simulation.json`
described in the frontend README, use the same common password, and add only
`--dart-define-from-file=.local-simulation.json` to the Android Studio run
configuration. A science icon appears on the login screen only in an explicitly
enabled debug build pointed at localhost, `127.0.0.1`, `::1`, or `10.0.2.2`.
It offers these observation perspectives:

- `denizkaraca`: complete musician profile and mature follow graph.
- `eceaydin`: incomplete profile and profile-completion guidance.
- `mertkoral`: cold start with no follows or autonomous behavior history.

Reports and restart checkpoints are written under
`build/reports/simulation`. They contain logical identities and diagnostics,
never the shared password or captured OTP values. If a FRESH run fails after
reset, correct the reported cause and run FRESH again. Use RESUME only after a
completed FRESH run has established compatible world and media checkpoints.
