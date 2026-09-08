# Venue and event analytics

Implemented September 8, 2026. This is first-party, pseudonymous product analytics using the existing PostgreSQL and Redis services. It does not require a new service, queue, SDK, or external analytics account. Collection is disabled by default. Owner reporting has a separate default-off launch switch; collection can continue while reports are hidden. No historical views are invented or backfilled.

## Definitions

- `impressions`: distinct viewer keys that reported a qualified event-card impression during the selected period. Venue totals deduplicate the same viewer across the venue's events.
- `detailViews`: distinct viewer keys that reported a qualified event-detail visit during the period. Loading an event through GET does not itself record a view.
- `profileVisits`: distinct viewer keys that reported a qualified venue-profile visit. Venue totals include all sources. An event's value includes only visits attributed to that event.
- Each metric is a **period-wide distinct count**, not a sum of daily unique counts. The same account/install across multiple days, reloads, event cards, and repeated observation IDs does not increase the relevant period unique count.
- Event-attributed profile visits need an eligible source event belonging to that venue and a same-actor detail observation in the preceding30minutes. Same-batch observations are processed chronologically, detail first on equal timestamps. Bounded merged validity windows preserve earlier proof for delayed cross-batch visits without inventing proof inside gaps. Invalid, unrelated, expired, or unproven attribution is ignored while the legitimate venue visit still counts once.
- Global profile visits are not the sum of per-event profile visits: one viewer may legitimately visit from multiple events.
- Authenticated venue owners and administrators do not contribute counts. Authenticated inactive/unverified/missing actors do not contribute. A supplied invalid/disabled bearer session cannot silently become a guest observation.
- Guest installation IDs are client-generated and untrusted. An account deduplicates across installations, but guest-to-login transitions, reinstallations, and different accounts can represent the same person more than once. A logged-out owner cannot reliably be recognized. These values are distinct pseudonymous visitors, **not certified unique humans, fraud-proof reach, or advertising billing evidence**.
- Visibility/time qualifications and foreground lifecycle are handled by the client. The backend verifies targets, identities, quotas, timestamps, attribution, and idempotency, but cannot independently prove that a human actually saw pixels.
- “Gidiyorum” remains an unavailable/“Yakında” UI action. No made-up going count is returned.

## API

All success responses use the existing `BaseResponse` envelope, with the following `data` values. Errors use the existing error envelope. POST and enabled owner GETs require Redis to be available. Owner reporting requires both the existing collection switch and `SOUNDCONNECT_VENUE_ANALYTICS_REPORTING_ENABLED=true`. When reporting is off, every owner reporting GET returns the existing9913/503 unavailable response with `Retry-After: 30` and `Cache-Control: private, no-store`, before Redis quotas or database reads. Reports remain authenticated routes. Enabled owner reads additionally perform current database ownership and active/verified-account checks on every request.

### Observation batch

`POST /api/v1/analytics/observations`

```json
{
  "clientId": "a839509d-d703-4f81-9385-d586dbd44980",
  "observations": [
    {
      "id": "18fb3786-4258-4703-8818-cde591a3cd65",
      "type": "EVENT_DETAIL_VIEW",
      "eventId": "c0890d3f-805d-4808-b123-abb9a9c79666",
      "observedAt": "2026-09-08T12:00:00Z"
    }
  ]
}
```

- Optional authentication. Authenticated identity comes only from the validated server principal, never a request user ID.
- Request body at most16KiB, measured while reading even if Content-Length is absent. The rate guard runs before body reading. No undeclared fields, scalar/enum coercion, duplicate JSON keys, duplicate observation IDs, zero UUIDs, or malformed/canonical-shortened UUIDs.
- One to20 observations per batch. All timestamps must be UTC ISO timestamps ending in `Z`. A **new** observation must be between server time minus24hours and plus2minutes, inclusive. Accepted existing receipts may retry beyond the24hour bound.
- `EVENT_IMPRESSION` and `EVENT_DETAIL_VIEW` require only `eventId` as target. They cannot also specify `venueId` or `sourceEventId`.
- `VENUE_PROFILE_VIEW` requires `venueId`, disallows `eventId`, and permits optional `sourceEventId`. Nullable optional target fields are accepted.
- Event targets must currently be venue-origin, calendar-approved, with an approved venue and active, verified venue owner. Venue targets must meet the same venue/owner eligibility. Unknown/ineligible/own targets are acknowledged without counts, so the endpoint does not reveal eligibility through different responses.
- Successful200 returns `{"acknowledgedIds":["18fb3786-4258-4703-8818-cde591a3cd65"]}`. IDs preserve request order. The complete batch is atomic; no successful response is sent before its receipt/presence transaction commits.
- Retry the exact same clientId, observation IDs, actor/session, targets, types, and timestamps after ambiguous network/503 errors. Existing matching receipts return success. A reused ID with a different actor or payload returns409, and rolls back any new observations in that batch.

### Owner summary

`GET /api/v1/venue-analytics/{venueId}?days=7`

```json
{
  "venueId": "58e05c32-bd79-4823-bbc0-58c53ea5aeea",
  "fromDate": "2026-09-02",
  "toDate": "2026-09-08",
  "days": 7,
  "timeZone": "Europe/Istanbul",
  "trackingStartedAt": "2026-09-08T12:00:00Z",
  "updatedAt": "2026-09-08T12:10:00Z",
  "metrics": {"impressions": 24, "detailViews": 9, "profileVisits": 5},
  "daily": [
    {"date": "2026-09-02", "metrics": null, "partial": false},
    {"date": "2026-09-03", "metrics": null, "partial": false},
    {"date": "2026-09-04", "metrics": null, "partial": false},
    {"date": "2026-09-05", "metrics": null, "partial": false},
    {"date": "2026-09-06", "metrics": null, "partial": false},
    {"date": "2026-09-07", "metrics": null, "partial": false},
    {"date": "2026-09-08", "metrics": {"impressions": 24, "detailViews": 9, "profileVisits": 5}, "partial": true}
  ],
  "comparison": {
    "status": "INSUFFICIENT_HISTORY",
    "currentFromDate": "2026-09-01",
    "currentToDate": "2026-09-07",
    "previousFromDate": "2026-08-25",
    "previousToDate": "2026-08-31",
    "currentMetrics": null,
    "previousMetrics": null
  }
}
```

`days` accepts only7,30,90 and defaults to30. Date bounds are inclusive Istanbul calendar days, ending today. `trackingStartedAt` is the durable server time of the first processed fresh collection batch from a permitted actor; null before collection starts. The initial batch may consist only of excluded own/ineligible targets, so this is collection-start metadata, not a first-view timestamp or a claim that all dates in the selected period have coverage. `updatedAt` is server read time. Counts are non-negative64-bit integers. Private responses have `Cache-Control: private, no-store`.

`GET /api/v1/venue-analytics/{venueId}/events/{eventId}?days=30` returns the same metadata plus `eventId`, with event-only metrics. The current event must still belong to the requested venue; deleted/unrelated events return404.

`daily` contains exactly `days` ascending Istanbul dates, including dates without observations. Each non-null metric is a distinct viewer count for that individual day; daily counts must never be added to derive period uniques. Before collection starts, metrics are null. After the known collection-start date, a day without facts returns zero metrics. Today is always partial. The collection-start date is partial if collection started after its Istanbul midnight. The first batch can contain delayed observations from the preceding 24 hours: real facts on an earlier day are retained in the series with `partial=true`; earlier days without facts remain null. These partial facts also remain in the existing period summary.

`comparison` uses two adjacent periods of **completed** Istanbul days: the latest `days` days ending yesterday and the preceding `days` days. These dates intentionally differ from the today-inclusive headline summary. Each period independently deduplicates viewers across days and events. Both metric objects are present only for `AVAILABLE`; all other statuses return both as null, without percentages or substituted zeros:

| Status | Meaning |
|---|---|
| `NOT_STARTED` | Collection-start metadata is null. This takes precedence over other reasons. |
| `RETENTION_LIMIT` | Either comparison period extends before the current retained 90-calendar-day horizon. Every 90-day comparison is unavailable, even if overdue cleanup has left older rows physically present. |
| `INSUFFICIENT_HISTORY` | Collection began after the previous period's first Istanbul midnight, including a partial first day. |
| `AVAILABLE` | Both date ranges are retained and collection began at or before the previous period's first midnight. An observed zero previous period remains a valid zero count; the API does not invent a growth percentage. |

Coverage uses global collection-start metadata. It does not prove uninterrupted collection for a venue: disabled collection, client delivery loss, outages, or incomplete adoption can leave gaps that this schema cannot distinguish from no observations. `AVAILABLE`, zero values and partial flags must be presented with that limitation. Summary, daily points, comparison and collection metadata share one read-only repeatable-read transaction. Daily values are grouped in one query with the summary, and comparison needs at most one additional bounded aggregation; there are no per-day queries.

### Owner event page

`GET /api/v1/venue-analytics/{venueId}/events?days=30&page=0&size=20&sort=REACH`

```json
{
  "content": [
    {
      "eventId": "c0890d3f-805d-4808-b123-abb9a9c79666",
      "title": "Akustik Gece",
      "eventDate": "2026-09-09",
      "metrics": {"impressions": 12, "detailViews": 6, "profileVisits": 3}
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false,
  "sort": "REACH"
}
```

Page0..1000, size1..50, default0/20. Lists live venue-origin/calendar-approved events, including past/future events; `days` filters the observation counts, not event dates. `sort` is the case-sensitive enum `DATE` (default), `REACH` (period impressions), `DETAIL_VIEWS`, or `PROFILE_VISITS` (event-attributed visits), and is echoed in the response. Unsupported values return400. Every sort uses eventDate DESC, startTime DESC, UUID ASC as stable ties; metric sorts first order by the chosen count descending.

Database LIMIT/OFFSET bounds the returned page. `DATE` keeps the indexed date page and one extra metrics query for its IDs. Metric sorts aggregate all eligible live events over the selected date range in PostgreSQL before pagination, including zero-count events; they do not sort a partial page or materialize the full event list in Java. Server enum constants select the sort column; request text is never interpolated into SQL. Unattributed profile visits and deleted/ineligible event buckets are excluded from the ranking aggregation. Count/content/metrics share one read-only repeatable-read snapshot. Database transactions have a5second timeout; overload is surfaced as retryable503 rather than unbounded work.

Event comparisons are raw distinct counts over the same selected calendar period. Events have different publication dates and exposure opportunities. Ranking does not establish a statistically significant winner, equal exposure, CTR, conversion funnel, attendance, revenue or return on investment. No additional collection, source tracking, action metrics or retention expansion is introduced by reporting.

## Failure and retry policy

| Code | HTTP | Meaning | Client action |
|---|---:|---|---|
|9910|400|Invalid semantic request/window/page|Drop/fix, no automatic retry|
|9911|409|Observation ID actor/payload conflict|Drop, do not mutate and reuse the ID|
|9912|429|Rate quota exceeded|Bounded retry after `Retry-After`|
|9913|503|Collection/reporting disabled or Redis/database unavailable|Bounded retry after `Retry-After` (30seconds)|
|9914|413|Body larger than16KiB|Split/fix, no automatic retry|

Malformed JSON/UUID/controller binding may use the application's existing400 error codes rather than9910. Invalid authenticated sessions produce401. No owner/ineligible target is exposed through a bespoke public response. Retry-After is an HTTP header, not a new body field; clients without access to this header should use bounded exponential backoff with jitter. Retries should be session-fenced and expire locally, not keep an unlimited offline queue.

## Storage, privacy, concurrency, and retention

The additive migration creates exactly four tables:

1. `tbl_venue_analytics_state`: singleton collection-start metadata.
2. `tbl_venue_analytics_receipt`: observation UUID plus HMAC actor/payload hashes for72hours. Receipt expiry is not refreshed by retries.
3. `tbl_venue_analytics_presence`: venue/event/source UUIDs, metric type, Istanbul day, and a32-byte per-venue HMAC viewer key. Retained for the current90-calendar-day horizon. No titles, usernames, emails, raw user/installation IDs, or raw IPs are stored here.
4. `tbl_venue_analytics_recent_detail`: one per-venue/event/HMAC viewer proof row, with normalized PostgreSQL timestamp multiranges; expires26hours after submission to support bounded delayed batches. Each detail contributes the closed30minute validity window beginning at its timestamp. Updates union valid windows and clip them to server time minus24hours through plus32minutes. Disjoint gaps stay unproven; overlapping windows merge, so repeat details cannot create unlimited history. The clipped horizon can hold at most50 disjoint windows of this form. PostgreSQL16's [multirange union, intersection, and containment operators](https://www.postgresql.org/docs/16/functions-range.html) perform these operations atomically.

HMAC-SHA256 uses a dedicated secret. Venue scoping prevents directly linking visitor keys across venues. Receipt/Redis key domains are separate from presence keys. IP addresses are used transiently through the existing trusted-proxy resolver and HMACed before Redis keys; untrusted forwarded headers do not override the peer address. These are pseudonymous records, not legally anonymous data.

Presence's composite primary key prevents duplicate daily facts. Period reads use DISTINCT viewer_key across days/events. A per-actor transaction advisory fence serializes overlapping same-actor batches, preserving detail-to-profile order; receipt locks are taken in stable UUID order. Different actors do not share a global application mutex. Persistence is restart-safe and independent of process-local maps.

There is deliberately no event foreign key/cascade on analytics. Deleting an event removes it from current event lists and event summary access, but its numerical observations remain in venue totals until ordinary90day retention. No event title is retained in analytics. Current venue ownership controls access even after ownership changes.

Cleanup defaults to a10second idle interval, with at most20 short transactions per run. Each transaction deletes at most1000 expired rows **per table**, using indexed expiry/day scans and SKIP LOCKED. It stops early when caught up. This bounds transaction size and catches up after restarts; it is not a guaranteed wall-clock erasure SLA under storage outage/overload. Backups have their own retention obligations. Configure and monitor cleanup throughput before increasing admission quotas.

`venueAnalytics` health is payload-free. When disabled it is UP without querying the uninstalled schema. Enabled schema/storage failures are DOWN with a generic reason. Cleanup backlog beyond the grace window reports DEGRADED. The exact component endpoint is `/actuator/health/venueAnalytics` when component visibility is configured. Default aggregate health may not promote custom DEGRADED status, so monitoring must explicitly watch this component/status. No external alert is automatically delivered by this implementation.

## Rate/scale boundaries

Current fixed60second Redis windows:

| Scope | Default |
|---|---:|
|Anonymous/authenticated POST requests per trusted client IP|300/minute|
|POST requests globally|3000/minute|
|Observations per installation/account actor|600/minute (batch cost is number of observations)|
|Owner reads per account|60/minute|
|Owner reads per trusted client IP|300/minute|
|Owner reads globally|3000/minute|

Each multi-key quota is atomic Lua. Rejected requests do not create fresh client counters or refresh exhausted window TTLs. Quotas fail closed if Redis is unavailable. Values bind through `app.venue-analytics.*` properties. Shared NATs and intentional installation-ID rotation are not perfect identity/fraud controls. Global max requests times max20 batch size is the upper admission bound, so tune quotas, pool/storage capacity, retention throughput, and alerting together. The existing pool/Redis/PostgreSQL infrastructure is reused, not magically unlimited.

The isolated fixture covers10,000 events and100,000 presence rows with first/middle/last/empty page boundaries and exact counts. It does **not** establish a production concurrency capacity, p95 latency, or a sustained ingestion SLA. Real deployment load tests and monitoring are still required for those claims.

The separate opt-in `venueAnalyticsLoadTest` exercises the new reports under a
bounded mixed PostgreSQL workload. Its synthetic fixtures, exact assertions,
recorded local timings and scope limits are documented in
[VenueAnalyticsLoadTesting.md](VenueAnalyticsLoadTesting.md). It does not replace
a production-like HTTP load test or the separate security/Redis suites.

## Deployment and reversible activation

1. Stop the local backend if required by the local workflow, verify the exact target database, and take a verified backup. Do not reset existing data.
2. Apply `scripts/db/2026-09-08-venue-analytics.sql` with the existing migration workflow, or explicitly via psql with ON_ERROR_STOP. It is additive/idempotent, transaction-wrapped, uses `public`, and has local lock/statement timeouts. `scripts/dev.ps1` includes it in the ordered local schema migrations. No analytics JPA entities rely on ddl-auto to create these tables.
3. Generate a dedicated random secret with at least32bytes of entropy and store it in the deployment secret manager/environment as `SOUNDCONNECT_VENUE_ANALYTICS_HMAC_SECRET`. Never commit or log a real secret. The config rejects missing/short/blank secrets when enabled.
4. Set `SOUNDCONNECT_VENUE_ANALYTICS_ENABLED=true`, deploy/restart backend, and verify Redis, the health component, actual observations and cleanup. Leave `SOUNDCONNECT_VENUE_ANALYTICS_REPORTING_ENABLED=false` until the owner statistics launch.
5. Keep the secret stable throughout the retained measurement horizon. Rotating it changes pseudonyms and can inflate distinct counts across the cutover; plan a versioned reset/cutover explicitly, not routine random regeneration at startup.

To stop collection, set enabled=false and restart. This does not delete historical records or modify events. Scheduled retention cleanup continues if the analytics schema is installed, even with collection disabled. A default-disabled installation without these tables is safely skipped through a schema-existence check. Rolling back code does not require dropping these additive tables, but a deployment that removes the cleanup code still needs an authorized retention job. Any destructive historical cleanup/drop requires a separate explicit decision and backup.

To reopen owner statistics later, set `SOUNDCONNECT_VENUE_ANALYTICS_REPORTING_ENABLED=true` in the deployment environment, keep collection enabled with its existing stable HMAC secret, and restart/redeploy the backend. Rebuild/release Flutter with `--dart-define=SOUNDCONNECT_VENUE_ANALYTICS_REPORTING_ENABLED=true`; changing a shell environment variable alone does not change an already compiled client. Reopening reports requires no schema/data migration. To hide them again, set only the server reporting switch to false and restart/redeploy first, then rebuild the client with reporting disabled. The server gate also blocks older clients from fetching new reports; previously displayed or captured data cannot be recalled. Collection, receipts, stored observations, HMAC identity and scheduled cleanup remain unchanged. These instructions do not edit a developer's existing local environment or restart a running server automatically.

Hiding reports does not pause or extend retention. Presence still expires outside the current90-calendar-day horizon; a launch months later cannot recover dates already cleaned up. Keep the same HMAC secret while collecting, and interpret the retained reports with the coverage limitations described above. Do not change retention or backfill history merely to reopen the UI.

## Verification

Run `./gradlew.bat --no-daemon test --tests '*Analytics*' --console=plain`. PostgreSQL/Redis tests create named disposable databases/containers and verify target URLs/ports before writes. No application database, mail pipeline, backend server, or deployment secret is used by these tests. Test fixtures explicitly enable reporting where reads are exercised.

Reporting launch-gate verification on September8: **228 passed, zero failures/errors/skips** (HTTP120, real-security-chain30, Redis5, service/HMAC/cleanup40, PostgreSQL33). The following command completed with approved Java/Docker access in3minutes7seconds, including a full recompilation:

```powershell
./gradlew.bat --no-daemon test --rerun-tasks --tests '*Analytics*' --console=plain
```

New regressions verify default-off configuration; disabled venue summary, event list and event summary responses before any Redis/database/clock access; private no-store unavailable responses; direct service read gates; normal observation acknowledgements and cleanup with reporting off; and reopening while still requiring collection enablement. Sandbox-only compilation could not resolve existing classpath entries; the same sources compiled and passed with approved execution, without unrelated source changes.

Earlier combined reporting verification, before the launch gate: **288 passed, zero failures/errors/skips**. This included221 analytics tests (HTTP116, real-security-chain30, Redis5, service/HMAC/cleanup37, PostgreSQL33) plus67 unchanged JWT/discovery/event mapping/public comments/venue suggestion regressions. The full targeted command was:

```powershell
./gradlew.bat test --tests '*Analytics*' --tests '*EventDiscovery*' --tests '*EventMapperTest' --tests '*EventUserControllerTest' --tests '*EventCommentReadSecurityTest' --tests '*VenueSuggestionSecurityTest' --tests '*JwtAuthenticationFilterSecurityTest' --console=plain
```

This run took1minute47seconds locally. The10k-event/100k-presence correctness fixture took2.103seconds including fixture insertion and assertions; this is **not** an isolated request latency or production benchmark. The tested migration SHA256 is `74DED8A3E9E5A42A3A6B0915E2B7829A1C2660BDAD2036489AA30E70FFC4D7F3`.

Covered: durable receipt retries/conflicts/rollback, concurrent repeats and reversed overlapping batches, exact period-vs-daily uniques, guest/account/install identities, current owner/admin/actor eligibility, public target eligibility, attribution and source forgery, deleted-event numerical retention, Istanbul boundaries, indexed bounded pages, migration repeatability, cleanup catch-up/health, HMAC separation, strict service bounds, failure-to503 mapping, actual Redis atomic costs/windows/global caps. HTTP/security-specific tests additionally cover route authorization, scoped body guards, strict JSON and cache/error headers.

Reporting coverage adds fixed7/30/90-day series; unknown versus zero days; first delayed batch facts; partial and exact-midnight starts; seven- and thirty-day comparison start boundaries; completed-day isolation from today's traffic; overlapping viewers/events; zero previous periods; the90-day retention refusal even with older physical rows; and all supported sort orders across first, tied, last, partial and empty pages. A concurrent PostgreSQL test commits new facts and collection metadata between report queries and verifies that summary, daily, comparison and metadata still use the same read-only repeatable-read snapshot. HTTP/security tests cover default and explicit sort binding, invalid sort rejection, response metadata/nulls, private caching and foreign-owner rejection.
