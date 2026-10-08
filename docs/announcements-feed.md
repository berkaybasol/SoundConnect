# Announcement integration in musician feed

The existing Promotion aggregate owns announcements (`tlb_promotion`, type `ANNOUNCEMENT`, placement `FEED`). The musician feed advertises a dedicated `ANNOUNCEMENT` renderer, targets the promotion UUID, and returns the shared URL-free `AnnouncementResponse` as its payload. The platform is represented by reason `PLATFORM_ANNOUNCEMENT`, with no account author or social-proof actors. Only item-scoped `HIDE` feedback is offered.

## Selection and continuation

Since algorithm `musician-v1.2.0`, new-plan admission also observes a six-hour
cooldown after a qualified impression and two impressions per rolling 24-hour
window, per account and announcement. Feed and directory impressions count;
delivery/prefetch and detail/video events do not. Counts and the last impression
are read in one existing analytics query at the session anchor. Already frozen
plans retain their continuation contract; this is not an atomic quota across
concurrently opened sessions. See [frequency boundaries](musician-feed-recent-views.md).

A new session walks all eligible announcements in bounded keyset batches of 160. Promotion owns audience, lifecycle, and attachment visibility checks. The feed excludes the viewer's persistent hidden identities before selection and reads qualified impressions through `AnnouncementImpressionHistory`. A streaming weighted reservoir retains three candidates plus a reference to the newest eligible announcement; every eligible older announcement has a chance, with weight `1 / (1 + qualified impressions)`. Only the newest eligible announcement receives the first position in the plan when its qualified impression count is zero. Once it has been seen, older unseen announcements remain in weighted selection and do not inherit early placement. Publication time is immutable across edits to previously published content.

Selection and gaps are seeded by viewer, feed-session UUID, announcement UUID, and purpose, independently of query iteration order. Publication-time ties use ascending announcement UUID to determine the newest identity. A signed cursor carries at most three `{id,gap}` entries. When the newest eligible announcement has no qualified impressions, it follows one or two normal items; every other gap varies from four through eight normal items. If that newest announcement has already been seen, all selected announcements use the normal four-through-eight gap, including older unseen ones. The selection is frozen even when impressions arrive later in the same session. Continuations resolve current visibility by the selected IDs, without depending on a latest-content window. Archived, no-longer-targeted, or hidden entries are omitted without drawing replacement identities.

The delivery ledger retains distinct announcement IDs and the number of normal items before the last announcement. The entire session delivers at most three distinct announcements. Normal items exclude announcements and promotions. Gaps restart from actual delivery rather than catching up after a delayed slot. Announcements and promotions do not appear next to either another announcement or a promotion. Insufficient normal content yields fewer announcements; announcements alone do not start an otherwise empty feed. All rules apply across page boundaries, including page size one.

## Visibility, hiding, and metrics

Stable identity is `ANNOUNCEMENT:<promotion UUID>` and feedback scope is `ITEM:ANNOUNCEMENT:<promotion UUID>`. Editing content does not reset a hide; a new UUID is independent. The announcement directory applies audience and lifecycle rules while deliberately retaining hidden announcements.

Exact cached pages recheck current announcement access and persistent hiding. Payloads contain media metadata and asset IDs, never expiring access URLs. The existing access-url endpoint resolves authorized attachments when needed. Existing feed `impressionToken` is the proof supplied to announcement analytics; the server derives the canonical delivery ID from it.

Qualified impressions are independent of deliveries. The shared analytics module owns impressions, account reach, source/profile/day attribution, and event idempotency. A successful announcement HIDE calls its engagement hook with the stable feedback row ID inside the feedback transaction. Client preview rendering does not constitute a delivery or an impression.

## Validation status

`2026-09-13-announcement-feed-plan.sql` runs after musician-feed feedback persistence. It preserves existing hides and updates the legacy Hibernate item-type check to accept `ANNOUNCEMENT`; plans themselves remain in signed cursors. Announcement integration originally introduced `musician-v1.1.0`; the subsequent musician-feed audit fixes use `musician-v1.2.0`. Prior-deploy continuations request a fresh feed through the established cursor-refresh path.

Focused tests cover streaming selection beyond the first 160 rows across fixed session seeds, newest-only first-exposure priority, seen-newest/older-unseen normal gaps, publication-time ties, weighted/stable selection, bounded plan validation, page-size-one continuation, mixed promotion separation, sparse feeds, hidden/withdrawn selections, exact replay checks, stable hide receipts, ledger-derived normal counts, and repeatable migration preservation. Execution is coordinated by the root task; no build or test outcome is claimed here.
