# Guest event discovery preview

The redesigned guest entry point uses an additive, read-only API. Existing event endpoints and invitation/publication flows are unchanged. There is no schema migration or data conversion.

## Contract

`GET /api/v1/events/discovery`

| Parameter | Rule |
| --- | --- |
| `date` | Optional ISO calendar date (`YYYY-MM-DD`). Defaults to today's date in `Europe/Istanbul`. Only today through today + 6 days are accepted. |
| `cityId` | Required UUID. |
| `districtId` | Optional UUID, filtered together with city. |
| `neighborhoodId` | Optional UUID. Requires `districtId`, filtered together with both ancestors. |
| `page` | Zero-based, default 0, minimum 0, maximum 1000. |
| `size` | Default 20, minimum 1, maximum 50. |

Malformed parameters and out-of-range values return the existing HTTP 400 error envelope. Well-formed unknown or mismatched location IDs return an empty page, never results from another city. Persisted inconsistent city/district/neighborhood hierarchies are also excluded.

The success envelope is `BaseResponse` with `data` containing `content` (existing `EventResponseDto` fields), `number`, `size`, `totalElements`, `totalPages`, and `last`. Empty and out-of-range pages retain the requested page number and page size. Sorting is `startTime ASC, id ASC`.

## Public data and date behavior

- The existing security policy permits anonymous GET requests under `/api/v1/events/**`. No authentication or security matcher changes are needed.
- Only venue-origin events from approved venues whose owners are active and email verified are returned.
- Selection is by the exact stored event calendar date, not a rolling 24-hour interval. An event that finished earlier today still belongs to today, consistently with date-based public weekly profile calendars.
- Only existing approved event performer links can expose a musician or group ID. Pending/rejected invitation targets are not joined. A pending or rejected performer remains a plain name snapshot, with null linked profile IDs.
- Event participation and profile publication permissions are not changed by discovery. A venue event does not need performer profile publication to be discoverable.
- UUID-backed posters are resolved using the existing batched public/READY media policy. Missing or private media is null and permits the frontend's default poster. Historical direct poster paths retain existing behavior.

## Query and rollback boundaries

The event page uses bounded SQL scalar projections and a matching count query, never a collection fetch join or full list fetched into memory. Its explicit venue publication predicate also matches the existing partial `(venue_id, event_date, start_time, id)` index. Posters are resolved in one batch for the current page. Discovery does not use group member labels, so this endpoint always returns an empty `bandMembers` array and does not query or expand group membership. Approved `bandId`, `musicianProfileId`, names and profile navigation remain unchanged. Other event endpoints and the existing event mapper retain their member-list behavior. A read-only repeatable-read transaction keeps page content, counts, and posters on one database snapshot.

Automatic frontend searching does not require a schema change. Pagination bounds response/event hydration, not all database work: exact counts and offset navigation still examine matching records, and a frontend debounce is not a server-side rate limit. The 10,000-event repository fixture checks paging correctness and query/entity-loading bounds, not concurrent-user capacity, production query plans or a latency guarantee.

Backend preview changes are isolated to the new `modules/event/discovery` package and its tests. The frontend can return to the prior screen while leaving this additive endpoint installed. If removing the endpoint later, remove only this new package and the matching test package after switching the frontend away from it. Do not revert unrelated working-tree changes or reset the database.

## Verification

Run `gradlew.bat test --tests '*EventDiscovery*'`. Service tests cover timezone rollover, the seven-day boundary, validation, paging, batched posters, omitted member expansion, consent-safe output and compatibility with the existing event mapper's manual-performer contract. HTTP tests cover the response envelope, defaults, filters and malformed parameters. Repository tests use an explicitly constructed disposable PostgreSQL container and check its actual datasource before writing any fixture. They cover exact-date selection, account/venue eligibility, location hierarchy, consent-safe links, legacy origin exclusion, stable paging/counts and zero entity graph loads. An efficient SQL-generated fixture inserts 10,000 events across two venues into that isolated database and checks first, middle and last pages of 20, exact counts, chronological/ID ordering, two queries per full page and no entity/collection loads.

No test or implementation step requires restarting the user's backend or touching their live database.

Latest performance-hardening verification: 37 discovery tests passed (14 service, 14 HTTP, 3 security-filter-chain, 6 real PostgreSQL including the 10,000-event fixture), together with 4 existing `EventMapperTest` tests and 1 `EventUserControllerTest` test. All 42 passed with no failures, errors or skips. Command: `gradlew.bat test --tests '*EventDiscovery*' --tests '*EventMapperTest' --tests '*EventUserControllerTest' --console=plain`. The initial preview also passed 15 existing event mapper/controller/venue-authority/security regressions. Restart the application to serve changed backend code, not for data migration.
