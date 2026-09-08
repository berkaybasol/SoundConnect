# Public venue suggestions

This endpoint lets visitors suggest a venue for the SoundConnect team to investigate. It does not register an account, create a venue/profile, claim venue ownership, or publish user content. Instagram, map links, contact data and client-selected recipients are not accepted.

## HTTP contract

`POST /api/v1/venue-suggestions`, JSON body:

```json
{
  "requestId": "c064cbd4-ff1e-4f0b-865a-ec7847081f7b",
  "venueName": "Örnek Mekan",
  "cityId": "908b9781-b75a-46dc-8b20-7ffbe71c6439",
  "districtId": "c303f065-5d07-48b9-9084-99b4f66e402b",
  "liveMusic": "UNKNOWN"
}
```

All five fields are required. IDs must be canonical UUID strings. `liveMusic` accepts exactly `YES`, `NO`, or `UNKNOWN`, not enum ordinals. Only JSON strings are accepted for these fields. Unknown fields are rejected. The UTF-8 request body is limited to 4096 bytes before JSON parsing, including chunked requests. Venue names are NFKC-normalized, trimmed and repeated spaces collapsed, then limited to 2–100 Unicode code points. Control, format and line-separator characters are rejected. The district must belong to the selected city.

HTTP 202 returns `BaseResponse` with `data: {"accepted": true}`. This means the suggestion and all recipient delivery jobs have committed durably to PostgreSQL. It does **not** mean an email has already reached an inbox. Transaction failures do not return success.

The frontend generates `requestId` once per submission and keeps it for retries after unknown/network outcomes. Replaying the same ID and normalized payload returns the same accepted outcome without creating another suggestion or recipient job. Reusing an ID for a different payload returns 409. A separate database-serialized 24-hour dedupe window suppresses suggestions for the same case-normalized venue name, city and district even with different request IDs or live-music answers. The first suggestion in that window is retained, not overwritten. No raw client IP is stored in suggestion or mail tables.

| HTTP | Error code | Meaning |
| --- | --- | --- |
| 400 | 9900 | Invalid normalized name, missing domain value, unknown/mismatched city and district. |
| 400 | Existing malformed/validation codes | Invalid JSON/types/UUID/enum, missing or unexpected fields. |
| 409 | 9901 | Request ID reused with a different payload. |
| 429 | 9902 | Distributed abuse limit reached. Includes `Retry-After`. |
| 503 | 9903 | Intake dependencies or commit temporarily unavailable. Includes `Retry-After: 30`. Retry with the same ID. |
| 413 | 9904 | Request body exceeds 4096 bytes. |

Only this exact POST route is added to anonymous security permissions. Other venue writes and suggestion reads/deletes are not made public. The route-specific guard uses Spring's decoded application-path semantics, so encoded ordinary letters and context paths cannot bypass the checks.

## Abuse protection

The existing trusted-proxy client address resolver is reused, including its configured proxy CIDRs and forwarded-header policy. Untrusted clients cannot choose their rate-limit bucket with `Forwarded` or `X-Forwarded-For`. Only a SHA-256 digest of the resolved address appears in Redis keys, which expire automatically.

One atomic Lua script checks and consumes all three windows: default 5 submissions per IP/hour, 15 per IP/day, and 200 globally/hour. The feature fails closed with 503 if Redis is unavailable or returns no decision. It has no process-local user/IP map or fail-open fallback. Quotas apply before body parsing and are also consumed for malformed submissions and retries, preventing an idempotency key from becoming a request-rate bypass.

Configuration under `app.venue-suggestions`: `hourly-limit` (1–10), `daily-limit` (1–30), `global-hourly-limit` (1–1000), `dispatch-batch-size` (1–20, default 5). Existing proxy trust configuration remains under `app.security.auth-rate-limit`.

## Mail recipient and reliability boundaries

Recipients come exclusively from the existing `soundconnect.admin-notifications.venue-application-emails` configuration used for venue applications. The current default is `backstage@soundconnect.com.tr,berkay@soundconnect.com.tr`. The environment override remains `SOUNDCONNECT_VENUE_APPLICATION_NOTIFY_TO`. Addresses are validated and case-normalized/deduplicated, with 1–10 required; invalid configuration fails startup rather than accepting suggestions without a recipient. Studio applications' existing fallback configuration is unchanged.

The existing confirmed RabbitMQ `MailProducer` and MailerSend adapter are reused with the new `VENUE_SUGGESTION_ADMIN` mail kind. Other mail kinds retain their previous semantics. The new kind uses a dedicated durable recipient fence, not the generic Redis-only mail deduplication:

1. Intake commits the suggestion, request receipt, 24-hour dedupe record and one mail row per configured recipient in one transaction.
2. A bounded worker leases due rows, publishes through the existing confirmed mail producer, and records broker confirmation independently per recipient.
3. The consumer atomically claims a recipient row as `SENDING` **before** calling the provider. It reconstructs destination, subject and body from stored server data. Queue-supplied mail fields cannot redirect this feature's emails.
4. Successful delivery is recorded as `SENT`. Redelivery and concurrent consumers never call the provider again for a claimed/sent recipient. Explicit provider 429 responses can retry with bounded backoff.
5. Timeout/ambiguous provider results, permanent failures, expired `SENDING` leases or retry exhaustion become `NEEDS_REVIEW`. They are not automatically resent. A definitive late success can settle the original fenced attempt.

The existing main mail queue expires jobs after 120 seconds. Therefore unconsumed broker-confirmed rows have a 10-minute watchdog; its republishing is bounded to **20 total publish attempts**, including successful broker confirms. Exhaustion parks the row for review. This protects against silent loss through queue TTL/DLQ cleanup while preventing infinite publishing when consumers are offline. Sender claims remain durable across all those queue copies. Database claim outages use 30-second delayed retries, bounded to 20, rather than hot requeue; durable outbox recovery remains the fallback.

External email does not support an atomic transaction with PostgreSQL. An unambiguous exactly-once delivery guarantee is therefore not claimed. The deliberate policy is **no blind duplicate-prone resend after uncertainty**, with durable evidence for an operator to investigate. A suggestion cannot disappear merely because one recipient's queue publish failed.

## Operations, migration and rollback

Apply `scripts/db/2026-09-08-venue-suggestions.sql` to the explicitly verified intended PostgreSQL database after taking a backup. It creates four additive tables and indexes, with no existing application-data conversion. It is repeatable without dropping data. Do not reset the database. Because the implementation uses JDBC rather than JPA entities, `ddl-auto:update` is not a substitute for this migration.

The backend must be restarted to register the new endpoint and mail handler. No new mail provider, credentials, or recipient configuration is needed. Existing PostgreSQL, Redis, RabbitMQ (including the existing delayed-message exchange) and the mail worker/provider configuration are required. Until migration/storage is available, intake returns 503; the worker logs a technical warning at most once per minute and health indicates a storage/migration issue.

The Actuator component is `venueSuggestionOutboxHealth`, inspect `/actuator/health/venueSuggestionOutboxHealth` where component details are enabled by deployment policy. It exposes only outstanding-work counts (`review`, `pending`, `stale`), never recipients, venue text, IPs or queued bodies. A custom `DEGRADED` status may not change an aggregate health status under the default status ordering. Monitor this component's review/stale counts explicitly. The code supplies logs and the health indicator, not a separate delivered alert integration.

For `NEEDS_REVIEW`, an authorized operator should inspect the durable row and provider logs before deciding whether any retry is safe. Do not mass-reset `SENDING` or `NEEDS_REVIEW` to `PENDING`: that can duplicate emails with ambiguous provider acceptance. Technical idempotency receipts and sent recipient fences are intentionally retained; any future retention policy must preserve replay protection.

For UI rollback, hide/remove the suggestion form while retaining the handler and durable tables so accepted jobs can complete. Do not delete the new mail-kind consumer before queued jobs are drained. No unrelated backend migrations, application senders, event logic or existing mail-kind behavior needs to be reverted.

## Tests

Focused command: `gradlew.bat test --tests '*VenueSuggestion*' --tests '*MailJobConsumerTest' --tests '*MailJobHelperTest'`.

PostgreSQL integration tests use an explicitly constructed disposable Testcontainers datasource, verify its actual URL/catalog before fixture writes, and never read application datasource configuration. Provider and mail producer calls are mocked. No verification step sends live email or modifies the user's application database.

Verified on 8 September 2026: 169 tests passed in the combined HTTP, validation,
rate-guard, service, PostgreSQL, dispatcher, delivery, actual security-chain and
existing generic-mail regression run. Three additional production-Lua tests on
an isolated `redis:7.2.5-alpine` container passed, including concurrent quotas
and expiry/counter behavior. Total backend verification: 172 tests, no failures,
errors or skipped tests.

After those PostgreSQL migration tests passed, the user separately authorized
the local deployment migration with their backend stopped. Root took and
verified a complete custom-format backup in
`.local-backups/venue-suggestions-20260908-013717/`, then applied the reviewed
script to `soundconnectdb` in the explicit `public` schema. Four new empty tables
and their indexes were verified. Existing user/event/band/connection-request
counts were unchanged. No live suggestion, email job or email was generated.
The backend was left stopped for the user to restart.

Verified focused run: 169 passing tests, zero failures/errors/skips. This includes 89 strict HTTP/body/path boundary checks, 13 rate-guard checks, 15 service/name/configuration tests, 15 disposable PostgreSQL persistence/concurrency/recovery tests, 12 mail-delivery fence tests, 4 dispatcher/health tests, the real security-filter-chain authorization boundary, and 20 existing generic mail/DLQ/helper regression tests.
