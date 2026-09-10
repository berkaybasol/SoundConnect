# Overthinking production contracts

Feed, artist and own-post pages ignore client property sorting. The server uses
the explicit feed order and a stable timestamp/UUID tie-breaker; private author
attributes never influence an order selected by the client. Page size is capped
at 50; page indices above 1000 are rejected. Profile share and reveal pages follow
the same bounded projections.

Artwork is restricted to HTTPS `i.scdn.co`, no credentials, no query/fragment,
port absent or 443, and `/image/` followed by a nonempty alphanumeric identifier.
Invalid new artwork is rejected with 9411. Every response drops invalid retained
artwork, and the production-safety migration scrubs it in storage. This includes
artwork obtained from the provider, direct post reads and profile shares.

Spotify enrichment runs once during creation, before the database transaction.
The transaction stores an authoritative snapshot when available; otherwise it
preserves validated client display hints and the canonical Spotify link without
binding an unverified local artist. Ordinary feed/detail/profile-share reads
perform no provider calls. A retained row without display metadata keeps its
canonical track link; it is not silently repaired by a reader's request.

## Creation retries

Clients send an optional UUID `clientRequestId` on post creation. Updated clients
must generate one key per logical publication and reuse the exact payload/key
after a lost response. Older clients without a key remain compatible but do not
gain duplicate prevention. Database advisory locks and durable receipts serialize
concurrent retries across API instances; a failed transaction reserves neither
the post nor the key. The payload fingerprint covers all submitted fields.

- Same owner/key/payload returns the original post and stored snapshot.
- Different payload with the same owner/key returns 409, code 9417.
- Retrying a deleted original returns 410, code 9418; it never recreates the post.
- Another owner has a separate key namespace.

Receipts survive ordinary post deletion and are erased with their owner account.
They contain a digest and identifiers, not the source text or music metadata.

## Reveal request quota

The requester has a 60-second interval between new requests and at most 10 new
requests in 10 minutes. An unchanged pending retry is free. Committed quota history
survives withdrawal and source deletion; failed transactions do not consume it.
The quota is requester-only: an author-specific limit would reveal whether two
anonymous posts share an author through differing 429 responses. Rejection is
HTTP 429, code 9419, with a `Retry-After` header. History older than one day is
removed by the scheduled cleanup.

## Migration and verification

After the shared notification/receipt schema and Overthinking lifecycle/outbox
migrations, apply these scripts in order using `psql -v ON_ERROR_STOP=1`:

1. `2026-09-10-overthinking-inbox-seen.sql`
2. `2026-09-10-overthinking-profile-shares.sql`
3. `2026-09-10-overthinking-production-safety.sql`
4. `2026-09-10-listener-account-erasure.sql`

The production-safety script adds creation receipts, reveal quota history, page
indexes and source-notification lookup indexes. It is transactional/rerunnable;
its bounded lock and statement timeouts require checking migration execution
against the deployment's actual retained data before rollout.

`OverthinkingLifecyclePostgresTest` checks real database ordering, atomic and
concurrent retries, deleted-operation replay, transaction/provider separation,
quota rollback, hidden-author quota parity and legacy-artwork sanitization.
`OverthinkingReadProjectionTest` checks bounded identity batches and page input
normalization. `OverthinkingRevealRequestPostgresTest` checks notification
retraction and retained receipts. Account erasure and delayed delivery have
separate real PostgreSQL suites. These tests do not establish actual Spotify
availability, deployed broker delivery or production migration duration.
