# Push delivery foundation and DM pilot

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


## Architecture

The domain transaction writes the inbox. Both `TransactionalNotificationService`
and `NotificationEventListener` synchronously publish `NotificationPersisted`
after `saveAndFlush`. The enabled `PushDeliveryPlanner` inserts per-device jobs in
that same database transaction. Failure aborts the inbox write; there is no
after-commit memory queue between the inbox and durable push jobs.

The database is the work queue. Workers atomically claim a due job with
`FOR UPDATE SKIP LOCKED`, receive a unique lease, and complete only their own
lease. Expired leases recover after a process restart. Jobs are claimed only
inside an available worker, never while waiting in an executor queue.

Before sending, a fresh short transaction rechecks source lifecycle, account
erasure/audience, message read/deletion, inbox visibility, device ownership,
permission, staleness, user preferences and expiry. HTTP runs outside that
transaction. This avoids holding domain/database locks during network latency.

FCM HTTP v1 uses official Google Auth with one FCM submission attempt per job
attempt, bounded authentication and HTTP deadlines, and no unbounded in-memory
queue. PostgreSQL owns FCM delivery retries with exponential backoff/jitter and
Retry-After. Authentication can make multiple requests: impersonated ADC first
refreshes the source user's credentials when needed, then obtains the service
account's access token. The library's custom retry toggle does not disable retries
for `ImpersonatedCredentials`; do not interpret it as a zero-authentication-retry
guarantee. Source-token refresh, IAM token exchange and FCM submission use the
deadline-aware transport and share the configured send deadline (8 seconds by
default, at most 10 seconds), with separately bounded connection/request timeouts.
The transport details and official references are in
`src/main/java/com/berkayb/soundconnect/modules/notification/push/transport/README.md`.

## Scope and privacy

- Only `DM_NEW_MESSAGE` is enabled in the first rollout. Both inbox entry points
  support future modules, which must be reviewed/tested before adding their types.
- Android registrations advertising `ANDROID_DM_V1` receive data-only DM
  payloads with the current permitted sender name and optional approved public
  avatar URL. Native `MessagingStyle` displays that sender with the fixed text
  `Sana bir mesaj gönderdi.`; original DM content and email are never included.
  The public lock-screen version is generic. Avatar loading has a fixed CDN
  allowlist, byte/pixel/time limits and a local fallback.
- Data includes notification ID, recipient ID, type, conversation ID, presentation
  version, send/expiry timestamps and the allowed sender presentation. The FCM
  routing token is the provider envelope address, not an exposed data field.
  Native delivery requires the current account binding; taps require the matching
  authenticated recipient and resolve current conversation identity from the API.
  Legacy registrations without the native capability still use the generic
  notification payload `Soundconnect / Yeni bir mesajın var.`.
- Inbox remains authoritative. The configured FCM collapse key can coalesce
  messages while a device is offline. Native deduplication/group counts describe
  cards actually accepted by Android, not every unread server message. The app
  reconciles on resume/reconnect; push does not replace history or unread counts.
- Device registrations are encrypted with AES-256-GCM. Only a SHA-256 lookup hash
  is indexed. Job rows contain neither plaintext tokens nor message content.
- Ownership/permission/revocation changes increment the device binding generation.
  Same-owner token rotation preserves queued work and uses the current token.
  A stale provider response cannot revoke a newly rotated token or complete a
  reclaimed lease. An invalid old token with a live replacement is retried.
- Soft account erasure deletes devices/preferences through a database trigger;
  foreign keys cascade associated jobs. Deleting inbox records also deletes jobs.
- Category preferences exist in the API; the initial mobile UI exposes the global
  push switch. Conversation-specific mute, quiet hours and message-preview options
  are later UX work, not implemented or implied by this pilot.

The final database check and external submission cannot be one atomic transaction.
A logout/read/erasure that occurs after that check can still race a push submission.
Already accepted OS alerts cannot reliably be recalled. Ambiguous network results
may retry; stable Android notification tags and APNs collapse IDs reduce visible
duplicates but do not constitute exactly-once delivery. Accepted, shown and read
are different states.

## States and operating controls

| State | Meaning |
| --- | --- |
| PENDING | Persisted and waiting until next attempt |
| IN_FLIGHT | Claimed by one worker until its lease expires |
| ACCEPTED | FCM accepted the request; no claim that the phone displayed it |
| SUPPRESSED | Expired, already read, unavailable source/device or disabled preference |
| DEAD_LETTER | Permanent provider/configuration error or attempts exhausted |

Authenticated OWNER/ADMIN roles (never LISTENER) can use:

- `GET /api/v1/admin/notifications/push/summary`
- `GET /api/v1/admin/notifications/push/failed?limit=25` (1–100)
- `POST /api/v1/admin/notifications/push/deliveries/{id}/retry`

Retry only requeues unexpired DEAD_LETTER jobs, rechecks policy at send time and
records operator/job IDs in the log. ACCEPTED/expired jobs cannot be replayed.
Responses never include raw device addresses, credentials or notification text.

Actuator `pushDeliveryHealth` reports disabled state or backlog/dead-letter
degradation. Existing HTTP health settings hide details. Protected summary is the
operator view; production monitoring should alert on degraded health, growing
backlog age and errors. Micrometer meters:

- `soundconnect.push.jobs` tagged by bounded outcome
- `soundconnect.push.provider.duration`

Default limits: 2 workers/provider slots, 20 send attempts/sec per API instance,
25 jobs per worker pass, 8 attempts, 2-minute lease, 24-hour message expiry,
30-second initial and 30-minute maximum exponential retry delay, 7-day terminal
history and 60-day stale-device retention. Provider Retry-After can exceed the
configured exponential cap; expiry remains the hard bound. Across multiple API
instances, budget the combined configured rate against the project's FCM quota.
No promise of throughput follows from these defaults; measure the expected peak.

## Guided development enablement

Setup order for a new environment. Preserve existing credentials and encryption keys
when maintaining an already configured environment:

1. Verify that **Firebase Cloud Messaging API** (`fcm.googleapis.com`) and
   **IAM Service Account Credentials API** (`iamcredentials.googleapis.com`) are
   enabled in `soundconnect-fa50e`.

2. Install/configure the Google Cloud CLI, then use its interactive local ADC
   login to impersonate the dedicated sender:

   ```powershell
   gcloud auth application-default login --impersonate-service-account=soundconnect-push-sender@soundconnect-fa50e.iam.gserviceaccount.com
   ```

   Preserve an existing ADC file; do not overwrite it merely to repeat setup.
   The documented
   Google Auth Java dependency supports impersonated ADC. The resulting local ADC
   file contains sensitive source-user refresh credentials even though it contains
   no service-account private key. Keep it outside the repository; do not put it in
   mobile files, commit it, or share its contents in chat or logs.

   Use normal ADC discovery for the CLI's default configuration directory. If a
   dedicated external configuration directory is chosen, set the backend's
   `GOOGLE_APPLICATION_CREDENTIALS` to the absolute path of that external ADC file.
   Leave
   `SOUNDCONNECT_FCM_CREDENTIALS_PATH` empty: that explicit-path branch accepts only
   service-account key JSON and must not point to an impersonated ADC file.
   See Google's [local ADC setup guide](https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment).
3. Review/apply `scripts/db/2026-09-22-push-delivery-foundation.sql`, followed by
   `scripts/db/2026-09-23-push-device-registration-revision.sql`, explicitly to
   the intended development database using its existing migration workflow.
   The script is additive and replayable. Startup does not run migrations.
4. Generate a random 32-byte encryption key, base64 encode it, and keep it in the
   local secret environment/secret manager. Back up the key securely with the
   database access procedure. Do not change it casually: existing device records
   need the original key. Key rotation needs a controlled re-encryption procedure
   or explicit registration reset followed by client re-registration.
5. After the preceding prerequisites are ready, set the backend environment:

   ```text
   SOUNDCONNECT_PUSH_ENABLED=true
   SOUNDCONNECT_PUSH_ALLOWED_TYPES=DM_NEW_MESSAGE
   SOUNDCONNECT_FCM_PROJECT_ID=soundconnect-fa50e
   SOUNDCONNECT_FCM_CREDENTIALS_PATH=
   SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY=<base64-random-32-bytes>
   ```

   Keep push disabled until these prerequisites are ready. Enabled startup fails
   on a missing encryption key, unavailable credential or missing migration instead
   of silently pretending to send. Successful startup alone does not verify the
   live IAM token exchange or FCM delivery.
6. Configure the mobile client as described in the frontend's
   `docs/push-notifications-setup.md`, then run with
   `--dart-define=SOUNDCONNECT_PUSH_ENABLED=true`. The backend may remain on the
   developer's computer; a USB Android device can use the existing adb reverse
   workflow. Internet access is needed for Google's service.
7. Test one DM end to end before enabling any additional notification type.

### Local launcher and credential mount

The local launcher includes the replayable push SQL at the end of its schema
migration list. `dev.cmd up` stops API/worker traffic before applying migrations.
On an empty database only the legacy base-schema bootstrap runs first, explicitly
with push disabled and without the ADC mount; the normal enabled start follows
the migration. This does not change Spring startup into a migration runner.

For local keyless setup, `.env.local` additionally has
`SOUNDCONNECT_FCM_ADC_HOST_PATH=<absolute external ADC file path>`. On Windows,
forward slashes in this dotenv path work with both Compose and Spring's properties
import. `dev.cmd up` selects `compose.push.local.yaml` only when push is enabled.
That overlay mounts the existing ADC file read-only into the API backend at
`/run/secrets/soundconnect-push-adc.json` and sets the container's
`GOOGLE_APPLICATION_CREDENTIALS` accordingly. It does not mount credentials into
the media worker, create a missing host file, or bake them into an image.
Enabled preflight checks the project, 32-byte base64 encryption key and external
ADC file; disabled push and recovery/read commands do not require an ADC file.

`dev.cmd boot` supplies the real `GOOGLE_APPLICATION_CREDENTIALS` process
environment variable for native Java. An IntelliJ Run/Debug configuration must
set that variable to the actual external ADC file itself: putting a similarly
named property in `.env.local` does not populate Java's process environment.
`dev.cmd idea` prepares infrastructure/schema only and does not modify an IDE
process that is already running. Do not run IDE and Docker backends simultaneously.

For production, use an attached service identity on a supported Google Cloud
runtime, or workload identity federation for an external runtime, with scoped
sender permissions. Do not deploy or copy the developer's local ADC refresh
credentials to a server. Production identity, environment separation and target
deployment validation remain later release prerequisites.

Client APIs derive the owner from the authenticated principal:

- `PUT /api/v1/user/notifications/push/devices/{installationId}`:
  `{token, platform:ANDROID|IOS, permission:AUTHORIZED|PROVISIONAL|DENIED|NOT_DETERMINED, appVersion?, clientRevision, presentationVersion?}`
- `DELETE /api/v1/user/notifications/push/devices/{installationId}?clientRevision=N`:
  idempotent; only revokes the requesting user's binding.
- `GET|PUT /api/v1/user/notifications/push/preferences`:
  `{enabled:boolean, disabledCategories:string[]}`. Categories use the existing
  NotificationType category names; unknown values are rejected.

The revision-aware backend requires both migration markers before push enablement.
`clientRevision` is a required integer from 1 through 9007199254740991 on both
device mutation routes. Missing, invalid and overflowing values fail with 400;
there is no unfenced legacy mutation path. Each new mutation receives a larger
revision from the client's durable installation state; retrying the same request
reuses its revision. An older or equal revision returns success without changing
the stored binding or payload. The client must atomically persist installation ID
and revision before the request, exclude them from backup restoration, and create
a new installation ID if that state is lost, corrupt or exhausted.

Logout stores a revoked tombstone even when the first registration has not arrived
yet. It also advances the installation-wide revision barrier when an obsolete
owner logs out, without revoking the current owner's token. Thus a delayed older
registration cannot undo a newer logout. Server-side `generation` remains the
separate ownership/permission/revocation fence for already queued deliveries.
Same-owner token rotation keeps that generation and uses the latest token.
The normal stale-device retention also bounds tombstone retention.

`presentationVersion` is optional. Android accepts `ANDROID_DM_V1` and
`ANDROID_NATIVE_V2` through `ANDROID_NATIVE_V10`; iOS and legacy clients leave it
null. Revocation clears the capability along with the token. The capability
selects presentation support, not account permissions, push enablement or the
notification type rollout allowlist. Delivery also requires
`app.notification.push.enabled` and inclusion in
`app.notification.push.allowed-types`. Source defaults keep push disabled and
allow only `DM_NEW_MESSAGE`; deployed settings must be checked separately.
Roll out the matching client and backend together; older clients lacking revisions
cannot mutate device registrations on the new backend.

Device register/revoke and preference updates share a Redis-backed per-account
limit (default 60 requests/minute, `app.notification.push.max-device-mutations-per-minute`).
Excess requests return 429 with `Retry-After`; unavailable Redis returns a safe 503
with a five-second retry indication before acquiring DB locks. The mobile logout
flow must still clear local account state when network revocation fails. Stored
device/tombstone rows are bounded at 100 per user by default
(`app.notification.push.max-stored-devices-per-user`), in addition to the existing
10 active-device limit. Registrations already owned by that user and revocations
remain possible at the storage limit; a new installation or ownership transfer
cannot grow the recipient owner's stored rows without bound.

## Failure diagnosis and rollback

For AUTH/PERMISSION/SENDER_ID/APNs failures, correct project credentials/config
before requeuing a small sample. For quota or provider outages, respect retry
timing and monitor backlog age. UNREGISTERED retires only the exact submitted
device token. Logs retain safe error codes, never raw FCM response bodies.

For immediate controlled suspension, set `SOUNDCONNECT_PUSH_ENABLED=false` and
restart the affected development/release instances. The normal inbox/DM remain
functional; pending jobs remain in PostgreSQL. Disable the mobile flag for builds
that should not register. Re-enablement suppresses expired jobs. Keep the additive
tables during application rollback; dropping them is not part of normal rollback.

## Verification and release gates

Run the focused suite without Firebase credentials:

```powershell
.\gradlew.bat -I scripts/gradle/push-verification.gradle test --no-daemon
```

To additionally exercise actual PostgreSQL ownership/lease/migration behavior,
use a disposable loopback database named exactly `soundconnect_push_test` and pass
`-Dpush.test.jdbc-url=jdbc:postgresql://127.0.0.1:<port>/soundconnect_push_test`
plus `-Dpush.test.jdbc-user=<test-user>` and its test password if required. Each
test creates/drops its own schema; it never reads `.env.local` or app datasource.
Without that explicit property, the PostgreSQL suite is skipped, not counted as
verified. DM's separate native PostgreSQL tests follow the same isolation rule.

Long natural idle/OEM battery behavior, broader physical-device coverage and
target-environment provider/load behavior remain bounded by the evidence, not
guaranteed delivery. For each application release target, perform representative
peak-load measurement and validate production identity/secrets, environment
separation, Android signing, migration, monitoring and backup/restore procedures.
Local module acceptance neither performs a production deployment nor completes
those application release gates. When the separately deferred iOS work resumes,
repeat the applicable checks on an iPhone and verify APNs configuration and signed
builds on macOS before an iOS release (current packages require iOS15).
