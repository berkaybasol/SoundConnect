package com.berkayb.soundconnect.modules.comment.repository;

import com.berkayb.soundconnect.modules.comment.entity.Comment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

/** Scalar fences: no managed target or OSIV-era visibility can override these checks. */
public interface CommentTargetAccessRepository extends Repository<Comment, UUID> {
    @Query(value = """
            select e.id from tbl_event e join tbl_venues v on v.id=e.venue_id
            join tbl_user u on u.id=v.owner_id
            where e.id=:id and e.event_origin='VENUE' and e.venue_calendar_approved
                and v.status='APPROVED' and u.status='ACTIVE' and u.email_verified
            for share of e,v,u
            """, nativeQuery = true)
    Optional<UUID> lockPublicEvent(@Param("id") UUID id);

    @Query(value = "select id from tbl_overthinking_post where id=:id for share", nativeQuery = true)
    Optional<UUID> lockPost(@Param("id") UUID id);

    @Query(value = "select owner_type as \"ownerType\",owner_id as \"ownerId\" from tbl_media_asset where id=:id", nativeQuery = true)
    Optional<MediaOwner> mediaOwner(@Param("id") UUID id);

    @Query(value = """
            select id from tbl_media_asset where id=:id and owner_type=:ownerType and owner_id=:ownerId
                and status='READY' and visibility='PUBLIC'
                and (nullif(trim(playback_url),'') is not null or nullif(trim(source_url),'') is not null)
            for share
            """, nativeQuery = true)
    Optional<UUID> lockPublicMedia(@Param("id") UUID id, @Param("ownerType") String ownerType,
                                 @Param("ownerId") UUID ownerId);

    @Query(value = """
            select visibility_choice_completed and visibility_mode='STANDARD'
            from "tbl_listener-profile"
            where (:byProfile and id=:id) or (not :byProfile and user_id=:id) for share
            """, nativeQuery = true)
    Optional<Boolean> lockListenerVisibility(@Param("id") UUID id, @Param("byProfile") boolean byProfile);

    interface MediaOwner {
        String getOwnerType();
        UUID getOwnerId();
    }
}
