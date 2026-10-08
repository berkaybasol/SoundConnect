# Post likers list

`GET /api/v1/likes/{targetType}/{targetId}/users?size=20&cursor=...` returns the existing `BaseResponse` envelope with `data.items`, `data.nextCursor`, and `data.hasMore`. Each item contains the canonical account `id`, `username`, public `avatarUrl`, and an optional `visibilityMode: GHOST` marker. The client uses the existing user-to-profile resolver when an identity is tapped.

Every page requires an active, verified viewer and reuses the existing target read/deletion fences. Profile shares use their own engagement identifiers (`EVENT_POST`, `TABLE_GROUP_POST`, `OVERTHINKING_PROFILE_SHARE`); underlying source reactions are never merged into them. Anonymous source authors are not consulted to construct liker identities. The list contains all current eligible likers, whereas an activity reason in the feed describes the viewer's followed likers.

Pages contain at most 50 identities and use one extra row to determine continuation without a total-count query. The cursor binds the target type and ID to a `(created_at, id)` seek position. It is a position, not an authorization token; access is checked again on every request. Shared identity resolution keeps pending listeners masked and ghost listeners restricted, and never uses musician stage names. Changes after the first request are reflected on later pages; refresh opens a new list.

Run `scripts/db/2026-09-12-like-users-pagination.sql` with PostgreSQL autocommit before deploying to an existing production database. The additive concurrent index leaves like rows unchanged. The local development migration list includes it, and Hibernate-generated development/test schemas declare the same index. No changes to like/unlike behavior are required.
