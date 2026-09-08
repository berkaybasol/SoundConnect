package com.berkayb.soundconnect.modules.comment.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommentAuthorRepository extends Repository<User, UUID> {

    /** Same ghost/pending policy as the shared identity resolver, without eager user/profile hydration. */
    @Query(value = """
            select l.user_id as "userId",u.user_name as "username",l.visibility_mode as "mode",
                l.visibility_choice_completed as "choiceCompleted",l.profile_picture_media_id as "avatar"
            from "tbl_listener-profile" l join tbl_user u on u.id=l.user_id
            where l.user_id in :ids order by l.id for share of l
            """, nativeQuery = true)
    List<ListenerIdentity> lockListenerIdentities(@Param("ids") Collection<UUID> ids);

    interface ListenerIdentity {
        UUID getUserId(); String getUsername(); String getMode(); boolean getChoiceCompleted(); UUID getAvatar();
    }
    /** One row per author, including business profiles; no collection fetch or per-comment query. */
    @Query(value = """
            select u.id as "userId",u.user_name as "username",u.profile_picture as "legacyAvatar",
                m.profile_picture_media_id as "musician",l.profile_picture_media_id as "listener",
                o.profile_picture_media_id as "organizer",p.profile_picture_media_id as "producer",
                s.profile_picture_media_id as "studio",v.avatar as "venue"
            from tbl_user u
            left join tbl_musician_profile m on m.user_id=u.id
            left join "tbl_listener-profile" l on l.user_id=u.id
            left join tbl_organizer_profile o on o.user_id=u.id
            left join tbl_producer_profile p on p.user_id=u.id
            left join tbl_studio_profile s on s.user_id=u.id
            left join lateral (
                select vp.profile_picture_media_id as avatar from tbl_venues venue
                join tbl_venue_profile vp on vp.venue_id=venue.id
                where venue.owner_id=u.id and vp.profile_picture_media_id is not null
                order by venue.created_at,venue.id limit 1
            ) v on true
            where u.id in :ids order by u.id
            """, nativeQuery = true)
    List<Candidate> candidates(@Param("ids") Collection<UUID> ids);

    interface Candidate {
        UUID getUserId(); String getUsername(); String getLegacyAvatar();
        UUID getMusician(); UUID getListener(); UUID getOrganizer(); UUID getProducer();
        UUID getStudio(); UUID getVenue();
    }
}
