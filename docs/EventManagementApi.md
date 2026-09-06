# Musician and band event management

Current contract, 2026-09-06. This change needs an application restart, not a database reset or new migration. Existing event-publication migrations remain prerequisites.

## Invitations and rejected invitations

`GET /api/v1/event-performer-requests/mine` retains the stable `PageResponse` envelope. Use paired `targetType=MUSICIAN|BAND` and `targetId`, `status=PENDING` for invitations and `status=REJECTED` for rejected invitations. Scope is authorized server-side. Pagination is bounded to page 0–100, size 1–50 (default 20), ordered by creation time and request UUID descending.

Pending and rejected historical rows remain queryable. Passing the event start does not rewrite a request status or send a synthetic decision notification.

Every request DTO includes:

- `decisionAllowed`: true only for a PENDING request whose event has not started.
- `canReconsider`: true only for a REJECTED request whose event has not started.
- `expired`: true for PENDING or REJECTED requests when the start deadline has been reached.
- `serverNow`, `eventStartsAt`: ISO-8601 UTC instants. The stored event date and local time are interpreted in `Europe/Istanbul`, not the host JVM timezone.

The server checks the strict `now < eventStartsAt` boundary after authority and event/request locks. Client flags and clocks are UX hints, never authorization. An expired new decision returns HTTP 409 / code 9255. Reload the affected page rather than retrying a mutation automatically.

`POST /api/v1/event-performer-requests/{id}/reconsider` requires an explicit JSON boolean `{ "showOnProfile": false }` (true is also accepted). Missing, null, numeric and string choices are rejected. This endpoint changes REJECTED to ACCEPTED; it does not edit any event title, date, time, venue or performer identity. The old `/accept` endpoint cannot reopen rejected requests. `/reject` cannot undo an accepted request.

PERFORMER_CONSENT can be accepted with or without personal/group publication. PROFILE_VISIBILITY retains its single-purpose semantics and can only be accepted with true. The current musician owner or active band founder must represent the exact target, and band authority is checked again while holding the band lock. Band membership alone does not grant decision authority.

A rejected PROFILE_VISIBILITY invitation may also have an entry in My Events: the existing venue connection already established participation and the profile link, and only profile publication was declined. A rejected PERFORMER_CONSENT request has no approved performer link and is not eligible for My Events.

A completed same-choice retry is successful even after the deadline because it performs no new decision. The immutable acceptance snapshot is compared, not the event's mutable visibility preference. Retrying acceptance or reconsideration after later profile hiding must never republish the event. Conflicting repeated choices return 409. Venue notifications are written transactionally to the outbox: REJECTED and subsequent APPROVED have distinct deterministic identities, and identical retries do not enqueue duplicates.

## My events and history

`GET /api/v1/user/event-profile-publications` adds optional `period`:

| Value | Meaning |
| --- | --- |
| ALL | Backward-compatible default, all eligible confirmed events |
| CURRENT | Not ended, with event date no later than today + 6 calendar days |
| FUTURE | Event date later than today + 6 calendar days |
| PAST | Event end is at or before the current time |

The event end is its stored date/time. If end time is absent or is an invalid legacy value before start, end defaults to start + 1 hour. An event still running over midnight through this fallback remains CURRENT until it ends. Period boundaries use one server time snapshot in Europe/Istanbul per list request.

Period and scope predicates are applied in PostgreSQL to both IDs and count before pagination, never by filtering one already-paged client list. CURRENT/FUTURE are chronological with UUID tie-breaking; PAST is reverse chronological with UUID tie-breaking. Existing ALL ordering is preserved. The same bounded page envelope applies. Existing musician/band calendar indexes support the scope/date predicates.

Implementation note: do not replace the normalized temporal SQL with a raw `event_date + start_time` versus `LocalDateTime` comparison. The existing `hibernate.jdbc.time_zone=UTC` setting can map epoch-based `LocalTime` and current-date `LocalDateTime` with different offsets on a non-UTC JVM. The period query binds `LocalTime.MIDNIGHT` through the same Hibernate mapping as persisted times, recovers logical seconds relative to that storage origin modulo 24 hours, and compares them with separately bound date/time components. This also preserves chronological ordering across midnight without changing global mappings or stored user data. Member publication choices are fetched in one bounded page query, not one query per band event.

Only venue-created events with approved participation, approved venue and no unresolved invitation are eligible. Deliberately hidden events are included in this private manager. The musician scope also includes approved band events only while the requesting musician is an active member. Each member's personal publication remains independent of the band profile's choice. Profile show/hide does not alter participation, venue links or event contents.

The invitation start deadline does not restrict the existing publication update API. A user may still withdraw publication permission from an already confirmed event after its end. The public weekly calendar's date window is unchanged; an event ending today can remain in that calendar until its date leaves the window or its visibility is withdrawn.

All list and decision responses carry private no-store cache headers. Actual device verification remains a separate manual test after restarting the backend and updated client.
