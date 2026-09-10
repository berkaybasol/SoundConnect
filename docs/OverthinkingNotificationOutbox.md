# Overthinking notification delivery

Reveal-request creation and decisions now persist a notification snapshot in
`tbl_overthinking_notification_outbox` in the **same database transaction** as the
domain change. Failure to save the snapshot rolls the domain change back. No
broker call occurs before commit. After commit, a bounded executor attempts fast
delivery; the scheduler recovers work after process exit, queue rejection, broker
failure or an expired worker lease.

The dispatcher uses the existing `NotificationProducer.publishConfirmed` and
shared notification consumer. A broker ACK is required before `PUBLISHED`.
Delivery is at least once: retry keeps the same random `event_id`, and the
consumer's permanent `tbl_notification_receipt.source_event_id` identity prevents
duplicates even after an inbox notification was deleted. A crash after ACK but
before the outbox status update can therefore safely redeliver the event.

## Deployment

1. Back up the database and stop API/domain writers and outbox workers during
   schema reconciliation. Do not run the upgraded binary against an unmigrated
   production schema or rely on Hibernate `ddl-auto` for production migration.
2. Ensure the shared notification schema and
   `scripts/db/2026-09-07-notification-replay-receipts.sql` are already applied.
   The outbox migration fails if the permanent receipt table is absent.
3. Apply `scripts/db/2026-09-09-overthinking-lifecycle.sql`, then
   `scripts/db/2026-09-09-overthinking-notification-outbox.sql` with
   `psql -v ON_ERROR_STOP=1 -f <script>`. Each script is transactional and
   rerunnable. The outbox script also reconciles an empty table previously
   created by Hibernate, installs canonical constraints/indexes and records its
   ID in `soundconnect_schema_migrations`. Invalid existing rows fail the
   transaction instead of being deleted or silently rewritten.
4. Start the updated API. Confirm `overthinkingNotificationOutboxHealth` is `UP`,
   submit a reveal request and a decision using test accounts, and verify that
   outbox rows reach `PUBLISHED` with one resulting inbox notification per event.
   Verify pending/rejected responses and notification payloads omit author
   identity. The received notification intentionally has neutral copy and keeps
   only the permitted requester ID, avoiding stale name/avatar snapshots after a
   listener changes ghost visibility.

`scripts/dev.ps1` registers both new scripts after existing migrations. Its
existing local bootstrap/application-stop behavior is unchanged. It is a local
development convenience, not a production rollout orchestrator.

## Retry, health and recovery

Configuration is under `app.notification.overthinking-outbox`, with environment
overrides named `SOUNDCONNECT_OVERTHINKING_NOTIFICATION_OUTBOX_*` in
`application.yml`. Defaults: 25 events per poll, two workers, queue capacity 250,
five-second polling, 30-second leases, eight attempts, five-second exponential
backoff capped at 15 minutes, and seven-day retention for published rows with a
matching durable consumer receipt.
Startup validates that the lease is at least the shared publisher-confirm
timeout plus one second.

The health component exposes counts and oldest pending/in-flight time only; it
does not expose payloads, author IDs or broker exception messages. It reports
`DEGRADED` for any dead-letter row or work older than 30 minutes. Database
inspection failures report `UNKNOWN` with only the exception class. Monitor this
component as well as the shared broker/consumer health; outbox ACK proves broker
acceptance, not that the recipient has opened the notification.

After repairing a persistent broker/configuration failure, inspect individual
dead-letter events and replay only the intended IDs:

```sql
UPDATE tbl_overthinking_notification_outbox
SET status = 'PENDING', attempt_count = 0, next_attempt_at = now(),
    lease_owner = NULL, lease_until = NULL, last_error_type = NULL,
    published_at = NULL, updated_at = now()
WHERE event_id = :'reviewed_event_id'::uuid AND status = 'DEAD_LETTER';
```

Preserve `event_id`, recipient, occurrence time and payload. Never manufacture a
new event ID during recovery or purge consumer receipts. The scheduler reclaims
expired leases automatically; do not clear an active worker's lease manually.
Only published rows with a receipt for the same event and recipient are removed
by retention. Pending/dead-letter work and broker-confirmed events still awaiting
consumer delivery remain available for recovery and reveal-withdrawal suppression.

## Privacy and deletion

Java and PostgreSQL enforce a strict payload allowlist. Received requests carry
the requester ID but no author/actor fields. Rejections carry no account identity.
Only approval carries `authorId`, routed to that request's recipient. Event IDs
are random rather than derived from either account. Author/requester names and
avatars are never stored in these notification snapshots. Status-specific
service checks prevent generating approval notifications for pending requests.

Outbox rows have no foreign key to a post, reveal request or account. Delivery
checks current account eligibility, source existence and matching reveal state
before claiming a receipt. Account/source locks remain held until the delivery
transaction commits. A withdrawn, deleted or superseded request cannot create a
new obsolete notification from delayed broker work. WebSocket and notification
mail delivery also revalidate current eligibility immediately before delivery.

Deleting a source post retracts its delivered notifications and its outbox
snapshots in the source transaction, while retaining permanent event receipts.
Account erasure removes snapshots involving the erased account. Receipts retain
only replay-fencing identifiers and must not be purged during recovery. An
identity already seen by a recipient before deletion cannot be recalled.

These deletion and replay semantics require the current application and the
inbox, profile-share, production-safety and listener-account-erasure migrations;
see [OverthinkingProductionSafety.md](OverthinkingProductionSafety.md).

## Rollback and validation

The schema is additive. Keep the outbox and permanent receipts through the
observation window; dropping them loses recovery or replay protection. If the
application needs rollback, stop Overthinking writes and drain/retain outstanding
outbox work before switching binaries. The old binary's direct, best-effort
publisher does not provide these delivery guarantees, so resuming writes with
that binary is not a safe durability rollback; prefer a forward fix.

`OverthinkingNotificationOutboxPostgresTest` uses an isolated, non-reused
PostgreSQL Testcontainer and verifies its JDBC URL/catalog before fixtures. It
checks commit/rollback, absence of pre-commit publishing, mandatory transactions,
broker failure and stable-ID recovery, expired-lease fencing, dead-letter
retention, migration reruns and SQL privacy/state constraints. Unit tests cover
privacy snapshots, executor rejection/starvation, retry/backoff, health and
configuration. These tests never read or connect to the application's local or
production database.
