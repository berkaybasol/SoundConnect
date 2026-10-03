# Studio reservation push and authenticated target

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


The durable studio domain outbox is documented in [StudioReservationOutbox.md](StudioReservationOutbox.md).
Native delivery adds six notification types with nine presentations. This is Android-only; iOS remains deferred.

## Required migration order

After the existing push foundation, registration revision, native venue capability and venue application migrations:

1. Apply `scripts/db/2026-09-24-studio-reservation-notification-outbox.sql`.
2. Apply `scripts/db/2026-09-24-push-native-studio-capability.sql`.
3. Start the new backend, then update the Android client.
4. Enable studio types only for the intended verification/rollout after the operational acceptance gate.

The capability migration is transactional/rerunnable and does not upgrade device rows or change allowed types.
It preserves `ANDROID_DM_V1`, `ANDROID_NATIVE_V2`, `ANDROID_NATIVE_V3` and adds `ANDROID_NATIVE_V4`.
Restricted venue application registrations support V3 and V4; they remain application-scoped until normal
authenticated promotion. V4 retains every prior native capability. Startup readiness checks the new marker
and both presentation/application-scope constraints. Default rollout stays DM-only.

## Push wire and source policy

`StudioPushPresentation` defines `ANDROID_STUDIO_V1`: exactly notificationId, recipientId, type,
presentationVersion, displayVariant, sentAt and expiresAt. The presentation contains no names, reservation
snapshots, contact, prices or free-form content. Android renders closed localized messages. Old Android
capabilities and iOS cannot fall back to a generic studio push.

`StudioPushEligibility` runs in the existing prepare transaction. It uses profile/room/reservation SHARE NOWAIT
locks, verifies the real identity chain and event snapshot, current owner/requester account/roles and listener
profile exclusion, then validates the type-specific state, time, decision/cancellation actor and archive/conflict
condition. Roleless requesters are excluded. Lock contention propagates to durable retry. No source locks remain
held over FCM HTTP I/O. Preparation is a check immediately before sending; an accepted provider message cannot
be recalled by a later source change.

Automatic conflict rejection is distinguished by the trusted domain event action; the reservation schema does
not separately store its reason. This limitation is explicit. Terminal history is not indiscriminately expired
by a future-only rule; the existing notification TTL still applies.

## Read-only target

`GET /api/v1/user/notifications/{notificationId}/studio-reservation` accepts the authenticated principal only.
`StudioReservationNotificationTargetService` uses one current PostgreSQL read snapshot for the owned notification,
source chain, account state and canonical roles. It denies listener role/profile, roleless, inactive, unverified
and erased callers. Owner events require current ROLE_STUDIO and studio profile ownership; customer events
require the reservation requester. IDs or ownerMode from the request do not authorize the read.

The target intentionally shows the **current** source, including later cancellation, past dates and archived
rooms. Started pending requests project EXPIRED without a domain update. Current studio timezone determines
local times, with the existing booking fallback for legacy invalid configuration. UUID lookup uses the primary
key; malformed payload UUIDs fail closed without a PostgreSQL cast exception.

Response fields: notificationId, recipientId, type, reservationId, roomId, studioProfileId, studioName, roomName,
ownerMode, status, roomArchived, completed, startsAt, endsAt, zoneId, localDate, localEndDate, localStartTime,
localEndTime. Date/time fields serialize as ISO strings. Phone, requester identity, prices and client request ID
are excluded. The endpoint never acknowledges a notification or changes a reservation.

Android native taps and Flutter inbox rows open the same exact detail. After a verified detail paints a visible
frame in the same active resumed session, Flutter acknowledges only that notification through the existing read
endpoint and reconciles only its card. Failed/hidden/stale responses do not fall back to the generic inbox.
Opening the inbox now refreshes instead of implicitly marking everything read; the explicit mark-all action remains.
