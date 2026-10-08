# Application mail intents

The existing venue application admin, studio application admin and studio terminal
decision mail now persist their send intent in the same transaction as the business
change. The existing templates, recipient configuration and empty recipient / missing
applicant email / nonterminal decision guards are unchanged. No venue decision mail
or new channel is introduced.

Apply `scripts/db/2026-10-06-application-mail-intents.sql` before starting this version.
It is additive and repeatable; it does not reconstruct historical mail. Missing storage
or required columns makes `applicationMailIntentHealth` DOWN. An enqueue failure aborts
the business transaction. SQL row details are deliberately omitted from propagated
errors because PostgreSQL constraint diagnostics can include private content.

## Transaction and identity

Enqueue requires an existing transaction (MANDATORY); it never commits independently.
The application service reads the recipient and all lazy relations while the persistence
context is open and serializes the complete `MailSendRequest`. Polling does not consult
the live application, recipient settings or a possibly changed terminal decision.

The database unique key is `(application_id, purpose, decision, recipient)`. Admin
creation uses `CREATED`; studio decisions use `APPROVED` or `REJECTED`. Duplicate
enqueue preserves the original row, snapshot, intent UUID and attempt budget. The
server-owned `applicationMailIntentId` is included in request params, hence also in
the existing generic mail dedup hash. Distinct applications, recipients and purposes
remain distinct. Caller-owned maps and entities are not retained by the worker.

## Dispatch and operations

There is one thread per process, no executor queue, and one scheduled batch at a time.
Each row is claimed immediately before IO, in a short independent transaction using
`FOR UPDATE SKIP LOCKED`. The lock is released before the existing `MailProducer`
waits for Rabbit correlated confirm and mandatory return validation. Outcomes require
the current lease token, PUBLISHING status and an unexpired lease. Old workers cannot
settle a reclaimed job. A malformed snapshot or a failed row outcome cannot block
the remaining batch. Restart recovers due PENDING and expired PUBLISHING rows.

| State | Meaning |
| --- | --- |
| PENDING, attempt 0 | Committed intent awaiting broker transfer |
| PENDING, attempt >0 | Retry scheduled after failed or unknown publish |
| PUBLISHING | A bounded lease owns the current broker attempt |
| PUBLISHED | Rabbit confirmed and routed the publish; provider/mailbox delivery is not asserted |
| NEEDS_REVIEW | Attempt budget exhausted, or invalid stored snapshot; no automatic retry |

Defaults under `app.application-mail`: `batch-size=5`, `max-attempts=10`,
`lease-seconds=120`, `retry-base-seconds=10`, `retry-max-seconds=600`,
`poll-delay-ms=1000`, `initial-delay-ms=15000`. Configuration values are bounded by
validation. Keep the lease longer than the configured broker connection/confirm
timeouts. Backoff doubles to its cap. Each intent captures its own maximum attempts,
so a later configuration change cannot silently reset existing budgets. The final
`max-1 -> max` claim is allowed; exhausted due/expired work terminalizes without an
extra send or increment. Active leases and future PENDING work are preserved.

Health reports pending/retry/publishing/review/stale counts, without content or
addresses. Review rows or unsettled work older than 30 minutes report DOWN. Follow
`last_error` codes (`publish_failed_or_unknown`, `publish_attempt_limit`,
`invalid_snapshot`); logs contain safe exception classes and intent/application IDs.
On storage trouble, verify migration and connectivity. On publish trouble, verify
Rabbit confirm/returns/mandatory settings and the configured durable exchange/binding/
queue. Inspect individual IDs; do not purge shared queues or reset active leases.
Terminal rows require an operator's incident assessment; there is no new public replay
endpoint. Preserve snapshots and evidence while investigating. Retention must account
for private mail content and the dedup identity; deleting old intent rows permits a
later repeated source call to create them again.

## Delivery limits

A crash after broker confirm and before the PUBLISHED outcome can republish the same
snapshot. The existing consumer's Redis sent key (default 900 seconds) and lock
(default 300 seconds) suppress duplicates within their available window. Redis read,
lock or mark failures permit delivery to proceed; expiry, fail-open, lost provider
responses and process failure can still duplicate external mail. This is at-least-once
source-to-broker transfer, not exactly-once provider/mailbox delivery. The existing mail
queue TTL, consumer retry and retained DLQ remain independent downstream contracts.
VenueSuggestion send/review policy and notification outboxes are unchanged.

## Validation

`ApplicationMailTransactionBoundaryTest` uses the same preexisting service API on the
original source and the candidate, with real PostgreSQL transactions. The store and
broker tests cover atomic commit/rollback/failure, concurrent identity/claim ownership,
immutable snapshots, restart, lease fencing, last attempt/exhaustion, migration replay,
actual Rabbit returns/persistent messages, and confirm/outcome loss. NACK and timeout
faults are explicitly simulated; the return/confirm/wire tests use actual Rabbit.

BIL-011 evidence contains the separate running JAR HTTP/JWT/PG/Rabbit/Redis acceptance
and local provider sink. The evidence-only fault fixture is not shipped in the JAR.
External provider and real mailbox acceptance remains outside this task. Physical
Vivo/FCM and mobile visual acceptance are inapplicable to this backend mail change.
