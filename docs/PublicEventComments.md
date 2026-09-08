# Public event comment reads

Guest event details can read comments without opening the generic social-comment API to anonymous users.

- `GET /api/v1/events/{eventId}/comments`
- `GET /api/v1/events/{eventId}/comments/{commentId}/replies`

Both return the existing `BaseResponse<Page<...>>` envelope, using `CommentResponseDto` and `CommentReplyResponseDto` respectively. `page` defaults to 0 and accepts 0–1000. `size` defaults to 20 and accepts 1–50. Invalid UUIDs, malformed parameters and values outside these bounds return HTTP 400. Root comments retain existing `createdAt DESC` ordering and replies retain `createdAt ASC` ordering.

The event must exist, be venue-origin and published to its venue calendar, and belong to an approved venue whose owner is active and email verified. There is no seven-day restriction for reading a known public event's comments. Missing, deleted, legacy musician-origin or otherwise ineligible events return the existing HTTP 404 `EVENT_NOT_FOUND` envelope. This additive policy does not change the legacy event-detail endpoint.

Reply reads additionally require the requested comment to be a root EVENT comment for exactly the supplied event. Missing parents, other target types, another event's parents and nested replies return HTTP 404 `COMMENT_NOT_FOUND`. A soft-deleted root may still have visible replies, consistently with existing comment behavior. Existing deleted-text placeholders and author identity/masking code are reused. The current authenticated viewer ID is forwarded; guests use a null viewer ID. Eligibility checks and existing page mapping run inside one repeatable-read transaction. It is deliberately not marked `readOnly`, because the existing privacy resolver takes shared row locks while reading identities and PostgreSQL rejects those locks in a read-only transaction. These endpoints do not perform data mutations.

Generic `/api/v1/comments/**` routes remain authenticated, including generic reads. Comment/reply creation remains the existing authenticated POST with optional `parentCommentId`; delete remains authenticated and ownership-checked. The existing method-scoped public GET rule for `/api/v1/events/**` permits these new reads but does not permit POST, DELETE or other mutations. No security matcher change, schema migration or live data conversion is needed. Restart the backend to register the new routes.

## Verification

`gradlew.bat test --tests '*EventCommentRead*' --tests '*SecurityConfigAuthorizationTest' --tests '*JwtAuthenticationFilterSecurityTest' --tests '*EventUserControllerTest' --tests '*CommentMapperGhostIdentityTest' --tests '*EngagementTargetValidatorVenueOnlyTest' --console=plain`

Verified: 58 tests passed, with no failures, errors or skips. New coverage comprises 12 service tests, 16 real-security-filter-chain/controller tests and 10 isolated PostgreSQL tests. The remaining 20 are existing authentication, security, event-detail, comment-identity and event-target regressions.

PostgreSQL tests construct an explicit fresh container datasource and verify its URL/database identity before inserting fixtures. One integration case commits its fixtures only into that disposable database, ends the test transaction and invokes the actual public adapter, existing comment reader and real identity resolver in the adapter's own transaction. It verifies non-empty comment/reply reads, actual privacy-lock repository calls and deleted-text placeholders. This catches PostgreSQL shared-lock incompatibility that an empty result or an ambient test transaction would miss. Tests neither start the application backend nor touch application data or email infrastructure.
