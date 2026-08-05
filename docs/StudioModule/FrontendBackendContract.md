# Studio frontend/backend contract

This matrix is the migration contract for replacing the Flutter Studio mock state. Presentation-only values such as colors, gradients, localized labels, formatted money, and `IconData` never cross the API boundary.

## Studio profile

| Flutter field or behavior | API field/source | Persistence/source of truth | Notes |
| --- | --- | --- | --- |
| `StudioProfile.id` | `id` | `tbl_studio_profile.id` | Stable aggregate ID; all child resources use UUIDs rather than names. |
| `userId` | `userId` | `tbl_studio_profile.user_id` | Owner identity; public follow/DM integrations reuse it. |
| `name` | `name` | `tbl_studio_profile.name` | Trimmed; first visible character normalized for display. |
| `description` | `description` | `tbl_studio_profile.description` | Legacy `descpriction` request alias remains temporarily supported. |
| profile image | `profilePictureMediaId`, `profilePictureUrl` | existing `MediaAsset` | Assignment validates `STUDIO_PROFILE` ownership, `READY`, and `IMAGE`. Public UI uses the resolved URL. |
| `address` | legacy response `adress`; client accepts both | `tbl_studio_profile.address` | Typo compatibility is removed only in a later API version. |
| `phone`, `website`, social URLs | same names | existing profile columns | Public contact action uses `phone`; no room/equipment duplicate. |
| `facilities` | ordered/display list | `studio_facilities` | Normalized unique labels. |
| Studio timezone | `timeZone` | `tbl_studio_profile.time_zone` | IANA ZoneId; UTC instants are rendered in this zone. It is mutable only until the first room is created, after which a change returns conflict to prevent booking shifts. |
| optimistic token | `version` | `tbl_studio_profile.version` | Sent with owner updates; stale writes return a stable conflict. |
| Spotify catalog | write `spotifyTrackIds`; read `spotifyTrackIds`, `spotifyTracks` | ordered IDs + server-authored JSON snapshot | Existing Spotify search/by-IDs API is reused. Incoming snapshot metadata is read-only/ignored; the backend resolves IDs and persists authoritative Spotify metadata in request order. |
| room count | `activeRoomCount` | count of active `tbl_studio_room` rows | Derived when the profile is read; never sourced from a global Flutter list. |
| backline unit count | `backlineUnitCount` | sum of active equipment `totalQuantity` | Derived when the profile is read; never persisted as a mutable profile counter. |

## Rooms

| Flutter mock/UI field | API field | Persistence | Validation/derivation |
| --- | --- | --- | --- |
| object identity | `id` | `tbl_studio_room.id` | Replaces name-keyed state. |
| create retry | `clientRequestId` | unique per Studio + immutable normalized-payload hash | Exact delayed retry returns the room's current representation even if the room was edited after creation; a different original payload returns conflict. |
| name | `name` | `name` | required, max 100, first character display-normalized. |
| short description (`type`) | `shortDescription` | `short_description` | optional, max 60; this is not a room-type enum. |
| capacity text | `capacity` integer | `capacity` | `1..100`; Flutter formats “kişi”. |
| hourly price text | `hourlyPriceMinor`, `currency` | minor-unit integer + ISO currency | optional, `1..100,000,000` minor units; no floating-point money, overflow, or syntactically valid fake currency code. |
| approval toggle | `reservationApprovalRequired` | boolean | affects only newly created reservations; snapshot stored on each reservation. |
| feature chips | `features[]` | ordered child rows | 0..8, max 60 each, case-insensitive unique. |
| photo carousel | ordered photo objects/URLs | media attachment rows | 0..10; existing MediaAsset pipeline. |
| list position/ten-room cap | internal `slotIndex` | partial unique active slot `0..9` | Not a user-editable field. |
| delete | archive command + `expectedVersion` | `archived_at` | Cancels/releases future activity, detaches photos, preserves history. |
| “reservation count / occupied hours” | schedule aggregates | reservation/occupancy query | Derived for the selected date range, not room columns. |
| owner/public daily availability | `todayAvailableHours`, `todayAvailabilityStatus` | daily occupancy projection | Returned authoritatively for both contracts; clients never fabricate missing values. |

Owner API: `/api/v1/user/studio-profiles/me/rooms`. Public API: `/api/v1/public/studio-profiles/{profileId}/rooms`.

## Reservations and manual owner blocks

| Flutter mock/UI field or action | API field/action | Persistence/source | Privacy and invariant |
| --- | --- | --- | --- |
| selected date/start/duration | command: `date`, `startTime`, `durationHours`; response: UTC `startsAt`/`endsAt` plus `localDate`/`localStartTime`/`localEndTime` | reservation UTC instants | Commands are interpreted in the Studio timezone. Additive local response fields keep Flutter rendering independent of the device timezone; UTC remains authoritative and backward compatible. Whole-hour, 1..4h, 09:00..23:00, <=365 days. |
| visible calendar boundaries | `todayLocalDate`, `currentLocalTime`, `latestBookableLocalDateTime`, `zoneId` on availability and owner schedule | one server clock snapshot in the Studio timezone | The client never derives booking rules from device-local `DateTime.now()`; backend validation remains final. |
| reservation create retry | `clientRequestId` | unique requester/client ID | Exact retry safe. |
| pending/approved boolean | `status` enum | reservation status | `PENDING_APPROVAL` or `CONFIRMED`; terminal states are explicit. |
| green/yellow cells | derived from status | API enum | Color remains Flutter presentation logic. |
| overlapping pending cards | owner schedule reservations | reservation rows | Pending overlaps are allowed. |
| exclusive green interval | unavailable interval | active occupancy row | PostgreSQL exclusion constraint prevents overlap. |
| owner approve | approve command + expected version | reservation + occupancy transaction | Conflict leaves the request pending and returns 409. |
| owner reject/cancel | transition command | reservation status; occupancy released if needed | Terminal states cannot reopen. |
| customer cancel | authenticated own-reservation action | same transaction boundary | Cannot cancel another user's reservation. |
| phone/off-app booking | manual block create | occupancy kind `MANUAL_BLOCK` | Uses the same exclusion rule and a client request ID. |
| remove manual block | release command + expected version | occupancy release metadata | History remains auditable. |
| reservation user name/avatar | owner response | requester projection | Never exposed by public availability. |
| “Kullanıcı No” | `requesterPublicCode` | immutable unique `tbl_user.public_code` | UUID is not used as the public-facing number. |
| profile navigation | requester user ID | existing public profile resolver | Resolve lazily on tap to avoid schedule-list N+1 queries. |
| message user | requester user ID | existing DM endpoint | No new messaging subsystem. |
| price shown in details | hourly and total snapshots + currency | reservation snapshot columns | Later room price edits do not rewrite history. |

Customer API: `/api/v1/user/studio-reservations`. Owner schedule/actions live below the owner room resource. Public availability returns only unavailable intervals and never guest PII.

## Backline inventory

| Flutter mock/UI field | API field | Persistence | Validation/derivation |
| --- | --- | --- | --- |
| identity | `id` | `tbl_studio_equipment.id` | Replaces equipment-name map keys. |
| create retry | `clientRequestId` | unique per Studio | Exact retry safe. |
| name | `name` | `name` | required, max 100. |
| main/subcategory strings | leaf category ID; response includes parent + leaf | global catalog FK | Parent is derived; strings are not duplicated on equipment. |
| brand/model combined UI | `brand`, `model` | separate columns | each optional, max 60. |
| description | `description` | column | optional, max 300. |
| technical chips | `features[]` | ordered child rows | 0..12, normalized unique. |
| photos | ordered photo objects/URLs | attachment rows | 0..5, existing MediaAsset pipeline. |
| total | `totalQuantity` | integer | `1..999`; cannot fall below an allocation for Studio-local today or a future day. Expired projection rows do not freeze the current inventory count. |
| available/reserved/maintenance fields | `available`, `busy`, `maintenance` | daily busy/maintenance only | Available is always derived; there is no mutable available column. |
| card status/color | counts + derived status | response computation | Gradient/color remains Flutter logic. |
| inventory-card current state | `todayAvailability` | Studio-local current date | Owner and public list/detail responses include exact counts plus the derived status; list hydration uses one batch day query. |
| inventory search | `query` | equipment + catalog text | Case-insensitive match across equipment name, brand, model, root-category name, and leaf-category name. |
| category filter | `categoryId` | global catalog FK | A root ID matches every child equipment; a leaf ID matches that leaf only. |
| availability filter | `availabilityBucket` | Studio-local current day | `AVAILABLE`, `BUSY`, and `MAINTENANCE` mean the requested quantity is greater than zero. Buckets are intentionally non-exclusive. |
| delete | archive + expected version | `archived_at` | Detaches media; history/audit remains. |

There are deliberately no backline price, deposit, usage-area, renter, checkout, or rental-request fields.

Owner API: `/api/v1/user/studio-profiles/me/equipment`. Public API: `/api/v1/public/studio-profiles/{profileId}/equipment`. Both list endpoints accept optional `query`, `categoryId`, and `availabilityBucket` plus `page`/`size`; reads are server-paged and deterministically sorted. A mixed daily row can therefore appear in both `BUSY` and `MAINTENANCE`, while a partially allocated row can also appear in `AVAILABLE`.

## Equipment daily availability

| Flutter behavior | API contract | Persistence/invariant |
| --- | --- | --- |
| no equipment selected initially | client-only selection state | No server field. |
| first date / last date range | inclusive `startDate`, `endDate` | Studio-local dates, not hourly instants. |
| selected source box | `sourceBucket` | `AVAILABLE`, `BUSY`, or `MAINTENANCE`. |
| target status | `targetBucket` | Must differ from source. |
| quantity | `quantity` | Must exist in source bucket on every selected day. |
| range retry | `clientRequestId` | Immutable availability-change audit row. An exact retry remains valid after the range becomes historical or the equipment is archived. |
| missing day | all units available | No default row is stored. |
| mixed busy + maintenance | both counts returned | Supported so long as their sum does not exceed total. |
| “partially available” and color | returned counts/derived status | Flutter uses a mathematical ratio for color; backend returns exact quantities. |
| long horizon | bounded request | Inclusive range cannot exceed 730 days. |

Past day rows are disposable calendar projections and are cleaned on deployment plus successful owner writes. Availability-change command rows are retained as immutable audit/idempotency history.

The service locks the equipment row, materializes/locks days in ascending order, validates every source count, applies all days, and commits once. An overlapping total edit uses the same lock.

## Global catalog and category requests

| Flutter field/action | API field/action | Persistence/invariant |
| --- | --- | --- |
| taxonomy constants | public category tree | global two-level catalog | Stable code/icon key, display order, active flag. |
| main category request | `ROOT_CATEGORY` | request row | No parent; may include 0..10 proposed children. |
| subcategory request | `SUBCATEGORY` | request row | Requires an active root parent; cannot contain children. |
| request name | `requestedName` | normalized alongside display text | max 160; duplicate pending requests prevented. |
| requester note | `requesterNote` | optional text | max 300; included in the exact-idempotency payload hash. |
| submit retry | `clientRequestId` | unique per Studio | Exact retry safe. |
| request timestamp | prefer `createdAtUtc`; legacy `createdAt` retained | UTC audit timestamp | The explicit `Instant` prevents device-local parsing of the legacy offset-less value. |
| “İncelemede” | real `status` | `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN` | UI localizes enum values. |
| owner withdraw | transition action | pending only | Decided requests are immutable. |
| SoundConnect review | admin approve/reject | one transaction | Approval resolves/creates global rows and stores resulting IDs. |

Public catalog: `/api/v1/public/backline/categories`. Owner requests: `/api/v1/user/studio-profiles/me/category-requests`. Review requires `MANAGE_BACKLINE_CATALOG`.

## Existing systems reused without duplication

| Studio UI area | Existing backend/frontend subsystem |
| --- | --- |
| profile image, room/equipment images | MediaAsset upload, verification, public URL, deletion guard |
| SoundConnect recordings | Track + ProfileMedia UI, extended with `STUDIO_PROFILE` owner authorization |
| Spotify picker/catalog | existing Spotify search/by-ID/by-IDs client and shared audio tab |
| message reservation guest | existing DM conversation flow |
| open guest profile | existing public user-profile resolver |
| follow counts/actions | existing follow cubits/endpoints |
| audio comments/likes | existing engagement module |

## Flutter replacement sequence

1. Add typed room/reservation/equipment/catalog repositories and models while leaving the existing mock UI renderable.
2. Load owner/public rooms by Studio ID, then switch room create/settings/archive mutations to API responses and versions.
3. Replace deterministic public availability and owner mock reservations/manual ranges with schedule endpoints.
4. Load paged equipment/catalog data; switch inventory CRUD and daily range commands.
5. Persist Studio Spotify state and route Studio media uploads to Studio Track/Profile endpoints.
6. Replace profile counters with loaded aggregate values.
7. Remove global name-keyed mock collections only after loading, empty, error, retry, 409 refresh, and stale-version UX tests pass.

Mock records are development fixtures only and must never be migrated into production data.
