# Studio domain architecture

## Scope and invariants

This module persists the Studio owner/public flows that were previously backed by Flutter-only mock state. It intentionally reuses the existing `StudioProfile`, media, profile-media, track, Spotify, DM, profile-resolver, and authentication modules.

The module does **not** model backline pricing, deposits, equipment checkout, or in-app equipment rental. Customers contact the Studio outside the application for backline rental.

Core invariants:

- A Studio has at most ten active rooms.
- Pending room reservation requests may overlap.
- Confirmed reservations and active owner-created room blocks never overlap.
- Room deletion is logical: future activity is cancelled/released while history remains auditable.
- Equipment availability is daily. `available = total - busy - maintenance` is always derived.
- An equipment quantity update and every calendar mutation serialize on the equipment row.
- Catalog categories are global and admin controlled; Studio owners submit review requests.
- Every owner read/mutation scopes the repository lookup to the authenticated Studio before any row lock. Foreign and missing aggregate IDs share the same not-found contract, in addition to controller role checks.
- Media assets remain owned by `STUDIO_PROFILE`; room/equipment photo tables are ordered attachment references.
- Room/equipment draft uploads use a session-fenced durable client cleanup queue. A successful aggregate mutation commits referenced IDs; cancellation, replacement, deterministic failure, or process restart retries the guarded media DELETE. The backend reference guard makes an ambiguous successful mutation safe: already-referenced assets are never deleted.

## Aggregates

### Studio profile

`StudioProfile` remains the aggregate for the existing profile fields. Additions are:

- `timeZone`: validated IANA zone, default `Europe/Istanbul`. It may be corrected only before the first room is created; this prevents a later profile edit from shifting already persisted UTC booking instants into a different wall-clock schedule.
- `version`: optimistic concurrency token.
- ordered Spotify track IDs. Client-authored track metadata is ignored; the existing Spotify service/API client hydrates the authoritative JSON cache before the short profile-write transaction is opened.

### Studio membership application

Studio registration creates an account without `ROLE_STUDIO` and uses the explicit account state machine:

- `PENDING_STUDIO_REQUEST -> ACTIVE` after an approved application and verified email;
- `PENDING_STUDIO_REQUEST -> REJECTED_STUDIO_REQUEST` after rejection;
- an already-active multi-role applicant remains `ACTIVE` when a later Studio application is rejected.

Application and applicant decisions take pessimistic locks in one transaction. OTP verification takes the same user lock and never promotes `REJECTED_STUDIO_REQUEST`, preventing a stale OTP transaction from overwriting an admin decision. Correct-password login for a rejected registration returns stable API error code `1112`; Flutter confines that session to the dedicated rejection/support route. Reapplication is not currently supported.

Application creation also locks the applicant and rejects the request when the account already has `ROLE_STUDIO` or owns a `StudioProfile`. Both sources are checked so inconsistent legacy role/profile data fails closed instead of creating a pending application that can never be approved.

Studio name/address/phone are normalized and validated again in the service before persistence, including the entity limits (100/255/canonical 11-digit Turkish phone). Admin/user history is bounded (`size <= 100`) and sorted deterministically: admin status queues use `applicationDate ASC, id ASC`; applicant history uses both fields descending. Rejection reason is a validated JSON request body and is never placed in a query string. Application/decision timestamps are stored as UTC wall-clock values and exposed as offset-bearing JSON instants.

### Room

`StudioRoom` contains Studio ownership, a database-protected active slot in `0..9`, a required create client-request ID, an immutable SHA-256 hash of the normalized creation payload, name, optional short description, capacity, optional hourly price in minor units, currency, reservation-approval policy, ordered feature labels, ordered photo media IDs, archive timestamp, and version. The create ID is unique per Studio; an exact delayed retry is checked against the immutable creation hash and returns the room's current representation even after later edits, while key reuse with a different original payload is rejected. Archiving detaches room-photo references so historical rooms do not indefinitely retain otherwise deletable media assets.

Hourly room price is optional and bounded to `1..100,000,000` minor units. Currency is normalized and validated with the JDK ISO-4217 registry; the database also bounds hourly and four-hour reservation snapshots so arithmetic cannot overflow or leave an unbookable room.

Feature positions are limited to `0..7`; photo positions to `0..9`. Photo assignment validates an `IMAGE`, `READY`, Studio-owned media asset through the existing media service.

Room-card daily metrics are calculated for the Studio-local current date and the `09:00..23:00` operating window with two grouped batch queries per room page, never per-room queries. Owner cards expose the count of `PENDING_APPROVAL` plus `CONFIRMED` requests, occupied/available hours, and the derived availability status. Public cards expose only available hours and a derived `AVAILABLE`, `PARTIALLY_AVAILABLE`, or `FULLY_BOOKED` status; they never expose reservation identity or occupancy type.

### Reservation and occupancy

`StudioRoomReservation` is the user request and audit record. It stores the requester, UTC instants, lifecycle status, approval-policy snapshot, hourly and total price snapshots in minor units, currency snapshot, client request ID, decision/cancellation metadata, and version. The create ID is unique per requester; exact retries return the existing reservation and a mismatched reuse is rejected.

`StudioRoomOccupancy` is the exclusive calendar fact. A row is either:

- `RESERVATION`, referencing one confirmed reservation; or
- `MANUAL_BLOCK`, created by the owner for a phone/off-platform booking. Manual blocks require a client request ID that is unique per creating owner; retry behavior is the same exact-payload contract used by room and reservation creation.

Only active occupancies participate in the PostgreSQL GiST exclusion constraint over `room_id` and the half-open range `[starts_at, ends_at)`. Approval-required requests have no occupancy until approved. Auto-confirm create and occupancy insert share one transaction.

Reservation transitions:

- create: `PENDING_APPROVAL` or `CONFIRMED`
- `PENDING_APPROVAL -> CONFIRMED | REJECTED_BY_STUDIO | CANCELLED_BY_CUSTOMER | CANCELLED_BY_STUDIO | EXPIRED`
- `CONFIRMED -> CANCELLED_BY_CUSTOMER | CANCELLED_BY_STUDIO`
- completion is derived from `endsAt <= now`; no periodic status writer is required
- terminal states never transition back

The booking window is Studio-local `09:00..23:00`, aligned to whole hours, one to four hours, starts in the future, and no more than 365 days ahead.

Reservation, occupancy, and public unavailable-interval responses retain the authoritative UTC instants and also expose `localDate`, `localStartTime`, and `localEndTime` calculated in the Studio timezone. This prevents device-timezone-dependent calendar rendering without weakening UTC storage or existing clients.

Both public availability and owner schedule responses include one internally consistent Studio-local booking-clock snapshot: `todayLocalDate`, `currentLocalTime`, and `latestBookableLocalDateTime`. Flutter therefore enforces visible date/slot boundaries without using the viewer device timezone; backend validation remains authoritative when a snapshot becomes stale.

### Equipment

`StudioEquipment` contains Studio ownership, global category/subcategory references, name, optional brand/model/description, total quantity, ordered features, ordered photo media IDs, archive timestamp, and version.

`StudioEquipmentDay` stores only non-default daily allocation exceptions. Missing rows mean all units are available. Counts are non-negative and `busy + maintenance <= total`.

A range command moves a quantity from one bucket (`AVAILABLE`, `BUSY`, `MAINTENANCE`) to another for every date in an inclusive range. It accepts a client request ID and appends an immutable command record for exact retry handling. The range is Studio-local, cannot start in the past, and cannot extend beyond 730 days.

Quantity reductions are constrained by Studio-local today and future allocations only; expired day projections never freeze the current inventory count. Expired `StudioEquipmentDay` rows are removed during the migration and opportunistically under the equipment lock on successful owner calendar/inventory writes. This bounds mutable projection storage to the supported forward horizon while immutable availability-command rows remain the audit and idempotency history. An exact command retry is resolved from that history before active-equipment or current-date validation, so response loss can be recovered even after the equipment is archived or the original range becomes historical.

### Backline catalog request

The catalog is a global, active/inactive, ordered two-level category tree. Equipment stores stable category IDs, never duplicated display strings.

A Studio request is either a root category (optionally with at most ten proposed children) or a subcategory under an active root. Lifecycle:

- `PENDING -> APPROVED | REJECTED | WITHDRAWN`
- decisions are immutable
- approval resolves/creates catalog rows and records the resulting IDs in the same transaction

Only `MANAGE_BACKLINE_CATALOG` may review requests. Owners can submit/list/withdraw only their own requests.

## Authorization matrix

| Operation | Access |
| --- | --- |
| Public room/equipment/catalog reads | anonymous, active records only |
| Create/cancel own room reservation | authenticated user; self-booking own Studio is rejected |
| Room, reservation, manual block, equipment, availability management | owning `ROLE_STUDIO` user |
| Submit/list/withdraw category request | owning `ROLE_STUDIO` user |
| Approve/reject category request | `MANAGE_BACKLINE_CATALOG` authority |
| Reservation guest identity fields | owning Studio only; never public |

## Transaction and race boundaries

- Room create/archive locks the Studio row. A partial unique active slot constraint is the final ten-room guard, and the per-Studio create-request uniqueness constraint is the idempotency backstop.
- Room/equipment ordinary edits require the expected version.
- Owner room, equipment, reservation, and backline-request locks include the authenticated tenant in the locking query. Public UUIDs therefore cannot be used to lock another Studio's rows before authorization. Customer cancellation similarly proves requester scope before taking the room/reservation locks.
- Spotify track metadata is resolved before opening the short profile-write transaction; the transactional executor then locks/rechecks the profile and persists the authoritative snapshot atomically. Upstream latency therefore never holds the Studio profile row lock.
- Reservation create serializes retries on the requester before locking the room. Approve and manual-block creation lock the owning Studio/room in deterministic order. Reservation and manual-block request-key uniqueness constraints are idempotency backstops; exact manual-block replay is resolved before active-room validation so a lost success response remains recoverable after room archive. The GiST exclusion constraint remains authoritative for overlap races and pre-checks are only for readable errors.
- Reservation cancellation locks the reservation and occupancy and updates both atomically.
- Equipment total/range changes lock the same equipment row. Day rows are locked in ascending date order.
- Category decisions lock the request and rely on normalized catalog uniqueness.
- Notification side effects are published after commit so rollback cannot emit a phantom event. They remain best effort because the current Rabbit producer has no durable outbox/reconciliation path; a broker failure after commit can permanently lose a Studio notification. Production remains blocked until the release gate is implemented and monitored.

## API surface

Existing Studio profile endpoints remain compatible.

- Owner rooms: `/api/v1/user/studio-profiles/me/rooms`
- Owner room schedule/reservations/blocks: `/api/v1/user/studio-profiles/me/rooms/{roomId}/...`
- Customer reservations: `/api/v1/user/studio-reservations`
- Public rooms/availability: `/api/v1/public/studio-profiles/{profileId}/rooms/...`
- Owner equipment/availability: `/api/v1/user/studio-profiles/me/equipment/...`
- Public equipment/availability: `/api/v1/public/studio-profiles/{profileId}/equipment/...`
- Public catalog: `/api/v1/public/backline/categories`
- Owner category requests: `/api/v1/user/studio-profiles/me/category-requests`
- Admin catalog review: `/api/v1/admin/backline/category-requests/...`
- User Studio application history: `/api/v1/user/studio-applications/my-applications?page=0&size=20`
- Admin Studio application queue: `/api/v1/admin/studio-applications/by-status?status=PENDING&page=0&size=50`
- Admin Studio rejection: `POST /api/v1/admin/studio-applications/reject/{id}` with JSON `{ "reason": "..." }`

List endpoints use deterministic sorting, `page <= 1000`, and bounded page sizes. Search strings are length-bounded and `%`/`_` are treated as literal characters rather than caller-controlled SQL wildcards. Public contracts never return owner-only media IDs or reservation guest PII.

## Migration and rollout

The repository does not yet have an authoritative Flyway baseline. The date-named SQL migration for this module is additive and idempotent, but production application remains gated on the live-schema reconciliation in `docs/ReleaseReadiness.md`.

Safe order:

1. Reconcile a schema-only dump and provision `btree_gist`.
2. Add user public code, `REJECTED_STUDIO_REQUEST`, Studio application/profile/timezone/version/Spotify structures, and review permissions; backfill rejected registration accounts and grant permissions idempotently to the existing `ROLE_ADMIN` and `ROLE_OWNER` rows because production data initialization is disabled.
3. Create and seed the global catalog.
4. Create room attachments, reservations, and occupancy with exclusion constraint.
5. Create equipment attachments, day allocations, and command audit.
6. Create category requests.
7. Deploy media-reference guard support before enabling attachment writes.
8. Provide an ingress/API rollout control, deploy the compatible backend, then move Flutter screens from mock to API one flow at a time. The repository has no built-in Studio feature flag; traffic control must be supplied and acceptance-tested by the deployment platform before rollout.
9. Keep production Hibernate at `validate`; remove legacy typo compatibility only in a later observed release.

Mock Studio data must not be backfilled into production.
