# Band pending invitations

`GET /api/v1/user/bands/{bandId}/invitations/pending?page=0&size=20`

This private, read-only list supports **Üyeleri Yönet → Bekleyen Davetler**. It does not change membership, event consent, notifications or the active/public band roster. Pending cards show the invited musician's username/avatar and **Onay bekliyor**. No cancellation or revocation operation is included.

## Authorization and privacy

The authenticated account must currently be an ACTIVE, email-verified MUSICIAN with an ACTIVE FOUNDER membership in the requested band. The service checks the current database account and membership, not just the JWT or frontend badge. Missing membership, inactive membership and unauthorized band access all return 403 without exposing invitees or band existence. Authorization, content and count use one read-only repeatable-read transaction snapshot.

The query projects only the invited user's ID, username and canonical musician profile photo media ID, using a left join that preserves invitations without a musician profile/photo. It does not load user entities or `band.members`. After authorization, the current page's distinct non-null media IDs (at most 50) are resolved in one batch through `MediaAssetService.getDisplayUrlMap`. Only PUBLIC, READY, displayable media is exposed. A missing/private/unfinished photo returns `profilePicture: null`; no legacy `User.profilePicture` or other profile type is used as a fallback. Empty/photo-less pages skip the media lookup entirely. No per-invitee user/profile/media lookup is performed.

The response still contains only the four fields below; internal media IDs are never serialized. Existing private/public `BandResponseDto.members` remains ACTIVE-only. Pending invitees must not be treated as active members. Photos are resolved from the current musician profile on every read, including invitations sent before this endpoint existed. No reinvitation or data migration is needed.

Successful responses are `Cache-Control: no-store, private`.

## Pagination and response

Zero-based `page` defaults to 0, with range 0–10000. `size` defaults to 20, with range 1–50. The offset `page * size` must not exceed 100000. Unsupported/non-integer values return 400. Client-controlled sorting is not exposed.

Rows are ordered by `coalesce(updatedAt, createdAt)` descending (nulls last), then membership UUID descending. A reinvitation therefore moves to its latest update position. Counts include only PENDING memberships in the requested band. As invitations can be answered between page requests, clients should deduplicate by userId, reset pagination when refreshing and refresh after sending an invitation. A page is not a cross-request immutable snapshot.

```json
{
  "success": true,
  "code": 200,
  "message": "Bekleyen davetler listelendi.",
  "data": {
    "content": [{
      "userId": "00000000-0000-0000-0000-000000000001",
      "username": "aedrum",
      "profilePicture": null,
      "status": "PENDING"
    }],
    "page": 0,
    "number": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

`page` and legacy `number` are identical. Empty and beyond-end pages return `content: []`; no fake active member is inserted. Acceptance/rejection removes an invitation from this list on the next read. Acceptance remains visible through the existing active-member roster.

Errors: unauthenticated 401, unauthorized role/account/founder 403 (service code **9217**), invalid pagination 400 (service code **9218**). Existing binding/security errors retain their standard API envelope.

No database migration is required. The existing unique `(band_id, user_id)` index scopes reads. The endpoint writes no rows and does not start background work.
