# Musician Feed v1

This document is the product and engineering source of truth for the first
Backstage feed shown to a musician account. Changes to the decisions below
require an explicit product decision; implementation details may evolve while
preserving these contracts.

## Product promise

The musician feed is one strong, server-ranked Backstage stream where a
musician follows their professional/social circle, discovers actionable
opportunities, and encounters relevant music content. It is not restricted to
musician authors. Eligible authors are musicians, bands, approved venues,
studios, and standard listeners. Organizer and producer profile types are out
of scope. Ghost listeners cannot publish and must never produce feed items.

There is no Following/Discover split and no feed composer in the first
production release. Existing top and bottom navigation remain unchanged.

## Eligible item families

- A followed account's public Track. Adding the Track to the public profile is
  the publication act; there is no second feed-share step.
- A followed account's public ProfileMedia gallery/video attachment. Attaching
  it to the public profile is the publication act. Avatars and other profile
  decoration are not publications.
- An OPEN, unexpired Collab listing. Publishing the listing is the publication
  act; Collab has no separate profile-share wrapper.
- An eligible future Event, including explicit performer-profile publication,
  a followed standard listener's Event profile post, and low-rate discovery.
- A followed standard listener's explicit Overthinking or TableGroup profile
  share. Raw/global Mainstage posts never enter Backstage. The card links back
  to its source module.
- Selected activity by followed accounts on Backstage-eligible targets:
  follow, like, and comment. Mainstage-only activity is excluded.
- Low-rate discovery from accounts the viewer does not follow.
- A conditional profile-completion carousel.
- Sponsored or editorial placements inserted by the sponsor mixer.

The viewer's own publications are excluded from their feed.

## Publication and engagement identity

Every item has a stable feed identity and a stable engagement target. These
must not be inferred from display text.

- Track and ProfileMedia engagement belongs to the underlying public
  MediaAsset target.
- Event engagement belongs to Event unless the item is a listener Event
  profile post, in which case engagement belongs to the Event-post wrapper.
- TableGroup profile-share engagement belongs to its share wrapper.
- Overthinking profile-share engagement belongs to its share wrapper. Existing
  source-post engagement remains untouched; profile-share UI must stop writing
  new reactions/comments to the source post.
- Collab keeps its native actions (save, apply, report, open/share) and does not
  gain synthetic likes/comments.

Removed likes/follows, deleted comments, inaccessible targets, closed/expired
listings, invalid events, deleted attachments, and restricted listener content
must not survive as feed candidates.

## Personalization

The first explicit personalization signals are:

1. opportunity city: "Which city do you want to see opportunities in?"
2. the musician profile's canonical instrument IDs

Genre is deliberately deferred. Opportunity city is not residence or a public
address. It is stored outside the location aggregate and references the
existing canonical City row. The Location and Collab core workflows remain
unchanged.

Missing preferences never block the feed. The mixer falls back to following,
freshness, quality, and limited exploration. Exact city + instrument Collab
matches rank first, followed by same-city opportunities, instrument matches in
other cities, and low-rate general discovery. Relevance is normally a soft
score; visibility, status, authorization, and safety are hard filters.
Opportunity city is also a soft relevance signal for eligible future Events
and Venue/Studio profile discovery; it is never a visibility filter.

## Completion carousel

The carousel contains all incomplete, user-actionable tasks and orders them by
actual impact:

1. opportunity city
2. instruments
3. stage name and bio
4. portfolio (at least one public READY Track or profile-media publication)
5. profile photo and social links

Backend state is authoritative and versioned. Internally, public profile
completeness and feed-personalization readiness are separate concepts even if
the UI presents a simple completed-step count. Completion is a nudge, never an
authentication or route gate.

## Ranking and mixing invariants

- Followed publications are the strongest social pool.
- A followed user's comment is a stronger reason than their like.
- Same-target likes/follows may aggregate into one reason row; comments remain
  individual. The strongest reason is primary and other activity is secondary
  social proof.
- Non-followed organic discovery appears substantially less often than followed
  content.
- Candidate providers are bounded. The feed mixer owns cross-source ranking,
  author/type diversity, deduplication, and cursor stability.
- Cards expose a machine-readable reason code; copy is rendered by the client.
- The viewer can hide an item with the close action. The overflow menu supports
  show-less, mute-author, and report. These actions are persisted and affect
  future ranking/eligibility.
- Discovery profile cards include Follow.

## Sponsorship

Sponsorship is delivery metadata, not a separate copy of native content. The
feed contract uses an optional promotion envelope around a native target, or a
standalone creative target. Supported architecture targets are standalone,
Event, Collab, Backline, and Profile; Track/Media may be added later through the
same provider/renderer contracts.

Paid content is labelled "Sponsored"; editorial platform selection is labelled
"Featured"; platform announcements use their own disclosure. In the musician
feed, sponsored profiles are limited initially to musician, band, venue, and
studio profiles. Standalone sponsored posts have CTA, hide, and report controls
but no likes/comments. Target-backed placements keep native actions.

Sponsor insertion rules:

- never in the first two positions
- never consecutive
- approximately one placement per eight to ten organic items
- per-user/per-campaign frequency caps
- no duplicate organic and sponsored rendering of the same target in a session
- sponsorship never bypasses target visibility, status, eligibility, or
  relevance

The existing Promotion module is a migration starting point, not yet a feed
campaign system. The first feed implementation may use deterministic
development fixtures behind an explicit non-production flag; production must
return no mock sponsor candidate. Initial real campaigns are admin-managed.

## Extension contract

The feed is extended by registering two independent components:

1. a backend candidate provider/resolver that returns bounded, authorized
   candidates and validates them again at hydration time;
2. a frontend card renderer for the versioned item type.

The core mixer, pagination contract, feedback model, analytics envelope, and
sponsor insertion policy must not require rewriting when a new item family is
added. Unknown item types are never served to clients that did not advertise
support for them.

## Operational quality

- Opaque, versioned cursors and a fixed feed-session anchor prevent ordinary
  pagination reshuffles.
- A successful signed continuation request is journaled atomically with its
  delivery rows. Retrying the same cursor, requested limit, and renderer set
  within the cursor/delivery TTL returns that exact committed page and tokens;
  conflicting request inputs at the same position fail closed.
- Replay idempotency is continuation-only. Initial feed loads have no stable
  client request identity and a retry intentionally creates a fresh session.
- Cursor schema/algorithm changes, active-secret rotation, and cursor expiry
  return stable error `MUSICIAN_FEED_CURSOR_INVALID` so the client refreshes
  from the first page; an incompatible prior-deploy response is never replayed.
- All multi-source reads use projections/batched hydration and bounded pool
  sizes; no entity graph walking or per-card network/database calls.
- Impression, open, CTA, follow, save/apply, hide, mute, and report events carry
  item, target, reason, algorithm, session, and position identifiers.
- Database changes are additive and forward-compatible with the previous app
  version. Runtime seeders are not production migrations.
- Feed rollout is feature-flagged and fails closed per optional candidate
  provider; one broken optional source must not corrupt the whole page.
- Authenticated feed reads are rate-limited per user before ranking begins.
  Initial loads and continuations use separate Redis-server-time token buckets,
  while both atomically reserve the same sustained page budget. This prevents
  new-session churn from creating an unbounded delivery ledger. Telemetry has
  its own budget. Redis failures fail closed with HTTP 503; policy rejections
  use HTTP 429, and both responses include `Retry-After`.
- Existing module regression suites plus feed contract, authorization,
  determinism, pagination, dedupe, and query-count tests are release gates.
