package com.berkayb.soundconnect.modules.marketplace.media;

import com.berkayb.soundconnect.modules.marketplace.support.MarketplaceAccess;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Marketplace authorization adapter for the shared media pipeline. No domain service dependency. */
@Component
@RequiredArgsConstructor
public class MarketplaceMediaAccess {
    // Eight attached photos plus a complete replacement set. Pending uploads count too.
    static final int MAX_OWNED_ASSETS = 16;
    private final MarketplaceAccess access;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public boolean canManage(UUID actor, UUID listing) {
        if (actor == null || listing == null) return false;
        access.requireBackstage(actor);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_marketplace_listing where id=:listing and owner_user_id=:actor)
                """, Map.of("listing", listing, "actor", actor), Boolean.class));
    }

    @Transactional
    public void requireUploadOwner(UUID actor, UUID listing) {
        lockActor(actor);
        access.requireBackstage(actor);
        // Every initializer and listing mutation uses this aggregate fence; concurrent
        // uploads cannot each observe the same free slot or race a draft deletion.
        if (jdbc.queryForList("""
                select l.id from tbl_marketplace_listing l where l.id=:listing and l.owner_user_id=:actor
                    and l.status in ('DRAFT','PUBLISHED','WITHDRAWN') and
                """ + MarketplaceAccess.eligibleListingProfileSql("l") + " for update of l",
                Map.of("listing", listing, "actor", actor), UUID.class).isEmpty()) throw hidden();
        Long count = jdbc.queryForObject("""
                select count(*) from tbl_media_asset m where m.owner_type='MARKETPLACE' and m.owner_id=:listing
                    and m.status not in ('FAILED','DELETION_PENDING','CLEANUP_PENDING')
                    and not (exists(select 1 from tbl_marketplace_report_photo r where r.media_asset_id=m.id)
                        and not exists(select 1 from tbl_marketplace_listing_photo p where p.media_asset_id=m.id))
                """, Map.of("listing", listing), Long.class);
        if (count != null && count >= MAX_OWNED_ASSETS) {
            throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_INVALID_REQUEST);
        }
    }

    @Transactional
    public void requireMediaAccess(UUID viewer, UUID listing, UUID asset) {
        if (listing == null || asset == null) throw hidden();
        lockActor(viewer);
        if (isModerator(viewer) && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_marketplace_listing_photo where media_asset_id=:asset)
                    or exists(select 1 from tbl_marketplace_report_photo where media_asset_id=:asset)
                """, Map.of("asset", asset), Boolean.class))) return;
        access.requireBackstage(viewer);
        // Hold lifecycle stable until signing. Even an authenticated owner cannot
        // use a stale ID after the aggregate was deleted.
        var owners = jdbc.queryForList("""
                select owner_user_id from tbl_marketplace_listing where id=:listing for share
                """, Map.of("listing", listing), UUID.class);
        if (owners.isEmpty()) throw hidden();
        if (owners.getFirst().equals(viewer)) return;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_marketplace_listing l join tbl_user u on u.id=l.owner_user_id
                    join tbl_marketplace_listing_photo p on p.listing_id=l.id
                    where l.id=:listing and l.status='PUBLISHED' and p.media_asset_id=:asset and
                """ + MarketplaceAccess.eligibleOwnerSql("u") + " and "
                    + MarketplaceAccess.eligibleListingProfileSql("l") + ")",
                Map.of("listing", listing, "asset", asset), Boolean.class))) throw hidden();
    }

    private boolean isModerator(UUID actor) {
        try {
            access.requireModerator(actor);
            return true;
        } catch (SoundConnectException denied) {
            if (denied.getErrorType() == ErrorType.MARKETPLACE_FORBIDDEN || denied.getErrorType() == ErrorType.UNAUTHORIZED) return false;
            throw denied;
        }
    }

    private void lockActor(UUID actor) {
        if (actor == null || jdbc.queryForList("""
                select id from tbl_user where id=:id and status='ACTIVE' and email_verified and erased_at is null for share
                """, Map.of("id", actor), UUID.class).isEmpty()) throw hidden();
    }

    private static SoundConnectException hidden() {
        return new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
    }
}
