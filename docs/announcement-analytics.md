# SoundConnect announcement analytics

Announcement observations travel through the existing authenticated analytics
collector, bounded client queue, rate guard, immutable receipt and HMAC identity.
Venue receipt hashes are unchanged so pre-deployment retry payloads still work.
No second analytics transport, reaction store or anonymous announcement tracker
is introduced.

## Observations and ownership

The collector accepts ANNOUNCEMENT_IMPRESSION, ANNOUNCEMENT_DETAIL_VIEW,
ANNOUNCEMENT_VIDEO_START and ANNOUNCEMENT_VIDEO_COMPLETE. Each carries an
announcement ID and FEED or DIRECTORY source. Feed observations must carry the
existing signed impressionToken and match its canonical user, content and
delivery time. Offline retries validate delivery expiry at the actual observation
time within the existing 24-hour queue window; this does not extend present-time
feedback authorization. Videos additionally use a playback UUID; completion requires a
previous start for that account, announcement, playback and source. Private
video media must currently be READY. Server publication and audience guards
exclude inaccessible content, and administrator previews do not contribute.

The UI uses the existing foreground and route-aware exposure component: at
least half the card must be visible continuously for one second. Detail views
are recorded after a successful detail load; opening comments is not a detail
view. Videos start from an explicit tap and report actual player progress.

Receipts make retries idempotent. Natural keys also deduplicate one feed
exposure per delivery and one start/completion per playback if a client
accidentally changes its observation ID. Occurrences and distinct account reach
are separate values. Period reach is calculated directly across the selected
period, never by summing daily unique values.

## Reporting

GET `/api/v1/admin/feed/announcements/{id}/statistics` requires current database
MANAGE_PROMOTIONS authority, even if a stale token still advertises it. It uses
a consistent database snapshot and private/no-store response. `from` and `to`
are inclusive Europe/Istanbul calendar dates; default is the last 30 days,
maximum 366 days, and dates before 1970 or in the future are invalid. Optional `profileType` and
`source` filters apply to total and daily series alike.

Like, comment and hide numbers come from successful server mutations. Immutable
attribution records capture the source at entity creation. The report joins
current reaction/comment/feedback rows, so removed likes and soft-deleted
comments stop contributing. These metrics describe surviving entities created
within the selected period, not every historical button tap. Hiders are unique
accounts. Clients predating source metadata contribute to the unfiltered total
as UNATTRIBUTED; they are never silently classified as directory activity.

The source header is scoped to each authenticated request; it is not a global
HTTP-client default. Normal profile/event reactions retain their old behavior.

## Lifecycle and operation

Archiving and manually ending publication preserve numeric history. Permanent
announcement deletion cascades its analytics rows; existing receipt TTLs remain
unchanged. Delivery IDs intentionally have no foreign key to the operational
feed ledger, whose retention must not erase archived announcement statistics.
Reporting indexes start with announcement ID and time; feed ranking history
uses the existing account HMAC and a bounded ID batch anchored at planning time.

The existing Analytics collection flag and identity configuration govern this
extension. The venue-owner reporting launch flag remains specific to venue-owner
reports: authorized admin announcement statistics work without exposing owner
reports early. Apply the migration before enabling the code. No new storage service, scheduler or
external analytics vendor is required. Raw announcement history is retained
until permanent content deletion; monitor table/index growth and reporting
latency during rollout rather than silently purging historical admin reports.

Verification lives in AnnouncementAnalyticsContractTest and
AnnouncementAnalyticsPostgresTest, alongside the existing collector regressions.
Test execution results belong in the final verification report, not inferred
from the presence of test files.
