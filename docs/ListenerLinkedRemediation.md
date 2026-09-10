# Listener-linked production remediation — 2026-09-10

## Profile and feed behavior

- Public listener profile shells, public identity resolution, and listener
  discovery require an ACTIVE, email-verified owner. Administrative/owner reads
  retain their separate contracts.
- Listener gallery mutation checks use a fresh locked scalar projection. An
  earlier Hibernate-managed STANDARD profile cannot turn a concurrent GHOST
  transition into an optimistic-lock 500; the content-lock domain response wins.
- Follow list avatars come from current listener media references in batches of
  at most 50. A removed/unavailable current avatar clears the obsolete user-level
  value. Ghost/pending/erased identity projections have precedence.
- Event audience profile rows contain publication-scoped engagement and the
  viewer's own attendance state. See EventAudienceIntents.md for the wire fields.

## Account deletion and shared conversations

Account erasure leaves a neutral, inactive account marker managed by the account
lifecycle implementation. Existing DM conversations remain resolvable by the
active participant; creating a new pair and sending new messages require both
current participants to be active and verified. An erased participant produces
HTTP 410 ACCOUNT_DELETED. DM previews expose otherUserDeleted=true and show
Silinmiş hesap without an avatar. Retained DM message content follows the agreed
shared-history policy; erasure is not a promise to retract messages already
received by another person.

A disabled or unverified peer rejects a new message with HTTP 403, preserving
the active sender's session. Only the caller's own authentication failure uses
HTTP 401; an unavailable peer must not trigger the app's global logout handling.

The account delivery fence reads scalar account status under stable PostgreSQL
UUID order and FOR SHARE. This excludes a concurrent account erasure transaction
through message persistence or delivery, without trusting a cached principal or
managed User entity. Existing-history lookup checks the caller while permitting
an erased peer. There is no separate inbound WebSocket message-write endpoint;
the actual REST message service and outbound DM event paths are guarded.

## Notification delivery

Notification consumption validates current account references and Overthinking
source post/request before claiming the durable replay receipt. Suppressed events
still keep their receipt but create no inbox entry. Received reveal notifications
require the request still be PENDING; approved/rejected events require the matching
current decision and recipient. Source shared locks precede receipt writes to
match source deletion lock order.

Follow and DM after-commit handlers queue bounded work instead of requesting a
second database connection on a commit callback. Notification WS/mail projection
also uses a bounded worker with a fresh transaction: it revalidates accounts,
source, and the current inbox row before output. Actor identity hydration joins
that transaction so its privacy locks remain held through output. Queue saturation
or shutdown can drop the best-effort realtime projection; the committed inbox
remains authoritative. This does not add durable delivery guarantees to follows.

Notification emails carry _notificationId. The final mail consumer loads that
inbox row and rechecks account/source eligibility while holding the same erasure
fence before calling the provider. Deleted or legacy uncorrelated notification
mail is suppressed. Actor-bearing mail is rebuilt from the current ghost-aware
notification projection as plain text; an old queued subject/body/HTML cannot
restore an actor identity hidden after the job was queued. Other mail kinds retain their existing delivery contracts.
Already delivered external messages cannot be recalled by this mechanism.

## Verification scope

Meaningful coverage includes the six-event SQL count slope, EVENT vs EVENT_POST
isolation, viewer attendance isolation, null/current/ghost avatar precedence,
PostgreSQL stale-entity visibility, inactive public discovery, queue rejection,
actual queued-snapshot erasure/source suppression, account/source row-lock races,
DM read-history versus new-send rules, replay receipt order, and final notification
mail suppression. Execution results are recorded in the main remediation report;
this document does not claim a passing run before those results exist.
