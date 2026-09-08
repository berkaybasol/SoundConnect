# Comment burst protection

Product decision, 8 September 2026: do not impose a pause after every comment/reply or a global 10-comments-per-minute account quota. Use a short, content-scoped burst limit instead. Three comments are not evidence that an account is malicious.

## Behavior

- A user may create three comments/replies on the same content within a rolling 30-second window. A fourth request waits until the oldest counted request leaves that window. There is no mandatory delay between the first three requests.
- The scope is `(user ID, target type, target ID)`. EVENT, MEDIA and OVERTHINKING are distinct. Different users and different content have independent limits. Root comments and replies share their content's limit, so changing reply threads cannot bypass it.
- A blocked request returns HTTP 429, application code `9356` (`COMMENT_BURST_RATE_LIMITED`) and `Retry-After` in seconds. Repeated blocked requests do not restart or extend the wait. Reading and deleting comments are not limited by this guard. Deleting a successfully created comment does not refund its send slot.
- Text, target access, user lookup and reply-parent validation precede quota reservation. A rejected create never writes a comment. A reservation belonging to a transaction that definitely rolls back is released. A committed or uncertain transaction is not refunded, because its comment may exist.
- When the guard's storage is unavailable, creation stops before the comment write with HTTP 503, code `9357` (`COMMENT_BURST_UNAVAILABLE`) and `Retry-After: 5`. This is different from a lost response after a possible write. The client preserves the draft and does not automatically retry either case.

The application uses the existing Redis connection. One atomic Lua operation uses Redis server time, prunes expired entries and reserves a unique request token in a bounded sorted set. At most three active tokens are kept per user/content key. Successful reservation refreshes the key's 30-second expiration. Rejections do not refresh it. Transaction rollback cleanup removes only its own token and is best effort if Redis itself is unavailable.

No comment text, profile photo, permanent bad-actor label, ban or moderation score is stored in this guard. Keys identify the user and content, and temporary values identify individual reservations. The guard requires no new database table, migration, external service or dependency.

## Boundaries

This is deterministic burst protection, not a general malicious-user classifier or a guarantee that spam cannot occur. It deliberately does not detect coordinated accounts, slow spam or activity spread over many different posts. Normal rapid conversation can reach the same short limit. Replies are not given an unrestricted exemption.

Redis expiration/restart/eviction can clear recent history. A cleanup failure can temporarily retain a rolled-back request's slot until the 30-second window expires. These are bounded, temporary restrictions, not durable disciplinary records. Redis limits coordinate requests across application instances but do not make the database POST idempotent. Network ambiguity after a dispatched write still requires checking the thread before explicitly resubmitting.

The existing Redis connection/command timeouts remain in use. No real application database, running backend or environment configuration is changed by this source update. Tests use disposable non-reused containers, not the application's Redis or PostgreSQL.

Verification results are recorded in [CommentAudit.md](CommentAudit.md). This work does not claim a production load test or real-device verification.
