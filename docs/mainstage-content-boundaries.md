# Mainstage content boundaries

`MediaAsset.contentAudience` is the canonical content destination: `MAINSTAGE` (listeners and industry profiles) or `BACKSTAGE` (industry content). It is independent of `visibility`, processing status, ownership and listener ghost privacy.

## Compatibility and classification

- Existing media defaults to `MAINSTAGE`, preserving music and performance publications. The schema has no reliable historical business-purpose field. Titles/descriptions are not automatically classified; old business media requires an owner to select `BACKSTAGE`.
- `STUDIO_PROFILE` media is always `BACKSTAGE`, including old rows migrated from the default and attempts to override it during upload/update. Collab is already a typed business publication; it currently has no media-owner enum or attachment schema.
- There is no generic post entity. Event publications, Overthinking sources and profile shares, and TableGroup profile shares retain their existing types and lifecycle rules.

## Media API

`POST /api/v1/user/media/init-upload` accepts the optional JSON field `contentAudience`. Omission keeps the legacy default. The exact owner authorization and upload lifecycle remain unchanged.

`PATCH /api/v1/user/media/{assetId}/content-audience` accepts:

```json
{"contentAudience":"BACKSTAGE"}
```

The field is required for this update. Existing owner authorization applies, including active band founder/manager membership. The update locks the media row, sharing the same serialization boundary as media engagement and attachment/deletion. It returns `BaseResponse<MediaResponseDto>` with the canonical value.

`MediaResponseDto` and `TrackResponseDto` include `contentAudience`, including upload completion, owner lists and public profile media/track responses. Existing request/Java constructors remain compatible.

## Read boundaries

Viewer restrictions derive from the authenticated `ROLE_LISTENER` context, never a client audience parameter. Listener requests must retain their Authorization header on endpoints in the historical `/public/` namespace. Guest and Backstage behavior remains compatible.

On these audience-aware optional-public GET routes, a supplied Bearer session that is invalid, expired, removed or unusable returns the existing 401 authentication error instead of silently receiving a guest projection. Requests without Authorization and unrelated public routes keep their previous behavior.

- Listener public media/track queries filter before pagination. Public asset detail and URL batches also recheck the current scalar database audience so a stale managed entity cannot revive changed content.
- Shared media likes/comments use the current audience predicate under the existing media row lock. Private/unlisted and unavailable assets remain inaccessible through public APIs.
- Studio search results, resolved studio profile targets, profile details, profile media, tracks, rooms, equipment and availability are unavailable to listeners. Owner management and existing guest access remain unchanged.
- Legacy promotion placement is `VENUE_MANAGEMENT_PANEL`; its writers have no validated listener audience/redirect contract. The listener response is empty. Audience-aware feed announcements continue using their separate canonical `targetProfiles` boundary.
- Overthinking source eligibility checks the actual author profile/role even when the response author is anonymous. Studio sources and sources referencing existing Backstage/studio media do not appear in listener source or repost lists, details, new reveal requests or source engagement. Missing historical track attachments do not erase retained public text. Filtering leaves eligible listener source IDs and anonymous/ghost identity rules intact.
- Comment/like author projection retains the discussion and counts while withholding studio profile identity and navigation from listeners. It does not delete comments, relationships or inbox history.

`BACKSTAGE` is an application content boundary. Existing `PUBLIC` CDN URLs remain public; this field does not convert storage to private or invalidate already shared public URLs. `PRIVATE`/`UNLISTED` storage and signed-access behavior remain separate.

## Deployment

`scripts/db/2026-09-14-mainstage-content-audience.sql` is registered in `scripts/dev.ps1`. It adds the non-null field/default/allowed-values constraint, backfills studio ownership, and records its migration ID. It inserts no mock content. This implementation work does not execute the migration against the application database.
