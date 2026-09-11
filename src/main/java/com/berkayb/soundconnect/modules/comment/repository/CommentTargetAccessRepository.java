package com.berkayb.soundconnect.modules.comment.repository;

import com.berkayb.soundconnect.modules.comment.entity.Comment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

/** Scalar fences: no managed target or OSIV-era visibility can override these checks. */
public interface CommentTargetAccessRepository extends Repository<Comment, UUID> {
    @Query(value = "select user_id as \"userId\",event_id as \"eventId\" from tbl_event_audience_intent where post_id=:id", nativeQuery = true)
    Optional<EventPostOwner> eventPostOwner(@Param("id") UUID id);

    @Query(value = "select id from tbl_user where id=:id and status='ACTIVE' and email_verified for share", nativeQuery = true)
    Optional<UUID> lockActivePostAuthor(@Param("id") UUID id);

    /** Shared listener publication boundary; read role/profile eligibility after the account lock. */
    @Query(value = """
            select exists (select 1 from tbl_user u where u.id=:id
                and exists (select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                    where ur.user_id=u.id and r.name='ROLE_LISTENER')
                and not exists (select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                    where ur.user_id=u.id and r.name in ('ROLE_ADMIN','ROLE_OWNER','ROLE_MUSICIAN','ROLE_VENUE',
                        'ROLE_STUDIO','ROLE_ORGANIZER','ROLE_PRODUCER'))
                and not exists (select 1 from tbl_musician_profile p where p.user_id=u.id)
                and not exists (select 1 from tbl_studio_profile p where p.user_id=u.id)
                and not exists (select 1 from tbl_organizer_profile p where p.user_id=u.id)
                and not exists (select 1 from tbl_producer_profile p where p.user_id=u.id)
                and not exists (select 1 from tbl_venues v where v.owner_id=u.id)
            )
            """, nativeQuery = true)
    boolean eligibleListenerPostAuthor(@Param("id") UUID id);

    @Query(value = "select owner_user_id as \"userId\",table_group_id as \"tableGroupId\" from tbl_table_group_profile_share where id=:id", nativeQuery = true)
    Optional<TablePostOwner> tablePostOwner(@Param("id") UUID id);

    @Query(value = "select id from tbl_table_group where id=:id for update", nativeQuery = true)
    Optional<UUID> lockTableGroup(@Param("id") UUID id);

    @Query(value = "select soundconnect_freeze_table_profile_shares(:id, null)", nativeQuery = true)
    int freezeEndedTablePost(@Param("id") UUID id);

    @Query(value = """
            select s.id from tbl_table_group_profile_share s join tbl_table_group t on t.id=s.table_group_id
            where s.id=:id and s.owner_user_id=:ownerId and t.id=:tableId and
            """ + com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareRepository.READABLE_PUBLICATION
            + " for share of s", nativeQuery = true)
    Optional<UUID> lockPublishedTablePost(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
                                        @Param("tableId") UUID tableId, @Param("now") java.time.Instant now);

    interface TablePostOwner {
        UUID getUserId();
        UUID getTableGroupId();
    }

    @Query(value = """
            select post_id from tbl_event_audience_intent where post_id=:id and user_id=:userId and event_id=:eventId
                and published_on_profile and intent<>'NONE' for share
            """, nativeQuery = true)
    Optional<UUID> lockPublishedEventPost(@Param("id") UUID id, @Param("userId") UUID userId, @Param("eventId") UUID eventId);

    interface EventPostOwner {
        UUID getUserId();
        UUID getEventId();
    }

    @Query(value = "select owner_user_id as \"userId\",source_post_id as \"sourcePostId\" from tbl_overthinking_profile_share where id=:id", nativeQuery = true)
    Optional<OverthinkingProfileShareOwner> overthinkingProfileShareOwner(@Param("id") UUID id);

    @Query(value = """
            select s.id from tbl_overthinking_profile_share s
            join "tbl_listener-profile" profile
              on profile.id=s.listener_profile_id and profile.user_id=s.owner_user_id
            where s.id=:id and s.owner_user_id=:ownerId and s.source_post_id=:sourcePostId
            for share of s
            """, nativeQuery = true)
    Optional<UUID> lockPublishedOverthinkingProfileShare(@Param("id") UUID id,
                                                         @Param("ownerId") UUID ownerId,
                                                         @Param("sourcePostId") UUID sourcePostId);

    interface OverthinkingProfileShareOwner {
        UUID getUserId();
        UUID getSourcePostId();
    }

    @Query(value = """
            select e.id from tbl_event e join tbl_venues v on v.id=e.venue_id
            join tbl_user u on u.id=v.owner_id
            join tbl_city city on city.id=v.city_id
            join tbl_district district on district.id=v.district_id and district.city_id=city.id
            join tbl_neighborhood neighborhood on neighborhood.id=v.neighborhood_id and neighborhood.district_id=district.id
            where e.id=:id and e.event_origin='VENUE' and e.venue_calendar_approved
                and v.status='APPROVED' and u.status='ACTIVE' and u.email_verified
                and e.event_date is not null and e.start_time is not null
            for share of e,v,u,city,district,neighborhood
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
