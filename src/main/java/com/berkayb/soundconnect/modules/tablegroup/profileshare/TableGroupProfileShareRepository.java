package com.berkayb.soundconnect.modules.tablegroup.profileshare;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TableGroupProfileShareRepository extends JpaRepository<TableGroupProfileShare, UUID> {
    /** New publications always require a currently open table and accepted membership. */
    String ELIGIBLE_SOURCE = """
            t.status='ACTIVE' and t.expires_at>:now
            and (t.owner_id=:ownerId or exists (
                select 1 from tbl_table_group_participants p
                where p.table_group_id=t.id and p.user_id=:ownerId and p.status='ACCEPTED'))
            """;

    /** A final snapshot also records that this publisher was eligible when the table ended. */
    String READABLE_PUBLICATION = "(s.final_source is not null or (not s.final_source_frozen and " + ELIGIBLE_SOURCE + "))";

    String PUBLIC_SHARES = """
            from tbl_table_group_profile_share s join tbl_table_group t on t.id=s.table_group_id
            where s.listener_profile_id=:profileId and s.owner_user_id=:ownerId and
            """ + READABLE_PUBLICATION;

    String ENDED_SOURCES = """
            select distinct t.id from tbl_table_group_profile_share s join tbl_table_group t on t.id=s.table_group_id
            where s.listener_profile_id=:profileId and s.owner_user_id=:ownerId and not s.final_source_frozen
              and (t.status in ('INACTIVE','CANCELLED') or t.expires_at<=:now)
            """;

    Optional<TableGroupProfileShare> findByOwnerUserIdAndTableGroupId(UUID ownerUserId, UUID tableGroupId);
    Optional<TableGroupProfileShare> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    @Query(value = "select s.* " + PUBLIC_SHARES + " order by s.published_at desc,s.id desc",
            countQuery = "select count(*) " + PUBLIC_SHARES, nativeQuery = true)
    Page<TableGroupProfileShare> publicShares(@Param("profileId") UUID profileId, @Param("ownerId") UUID ownerId,
                                              @Param("now") Instant now, Pageable pageable);

    @Query(value = "select s.* " + PUBLIC_SHARES + " and s.id in (:ids) order by s.published_at desc,s.id desc", nativeQuery = true)
    List<TableGroupProfileShare> publicSharesByIds(@Param("profileId") UUID profileId, @Param("ownerId") UUID ownerId,
                                                  @Param("now") Instant now, @Param("ids") Collection<UUID> ids);

    @Query(value = ENDED_SOURCES + " order by t.id", nativeQuery = true)
    List<UUID> endedSources(@Param("profileId") UUID profileId, @Param("ownerId") UUID ownerId, @Param("now") Instant now);

    @Query(value = ENDED_SOURCES + " and s.id in (:ids) order by t.id", nativeQuery = true)
    List<UUID> endedSourcesByShareIds(@Param("profileId") UUID profileId, @Param("ownerId") UUID ownerId,
                                    @Param("now") Instant now, @Param("ids") Collection<UUID> ids);

    @Query(value = "select soundconnect_freeze_table_profile_shares(:tableId, null)", nativeQuery = true)
    int freezeEndedSource(@Param("tableId") UUID tableId);

    @Query(value = "select exists(select 1 from tbl_table_group t where t.id=:tableId and " + ELIGIBLE_SOURCE + ")",
            nativeQuery = true)
    boolean eligibleSource(@Param("tableId") UUID tableId, @Param("ownerId") UUID ownerId, @Param("now") Instant now);

    @Query(value = "select id from tbl_table_group where id=:tableId for update", nativeQuery = true)
    Optional<UUID> lockSource(@Param("tableId") UUID tableId);

    @Query(value = "select id from tbl_user where id=:userId and status='ACTIVE' and email_verified for update", nativeQuery = true)
    Optional<UUID> lockActor(@Param("userId") UUID userId);

    @Query(value = "select id from tbl_user where id=:userId and status='ACTIVE' and email_verified for share", nativeQuery = true)
    Optional<UUID> lockActiveReader(@Param("userId") UUID userId);

    @Query(value = "select user_id from \"tbl_listener-profile\" where id=:profileId", nativeQuery = true)
    Optional<UUID> listenerOwner(@Param("profileId") UUID profileId);

    @Query(value = """
            select id as "profileId",visibility_mode as "mode",visibility_choice_completed as "choiceCompleted"
            from "tbl_listener-profile" where user_id=:userId for share
            """, nativeQuery = true)
    Optional<Visibility> lockVisibility(@Param("userId") UUID userId);

    @Query("select s.tableGroupId from TableGroupProfileShare s where s.id=:shareId and s.ownerUserId=:ownerId")
    Optional<UUID> ownedSourceId(@Param("shareId") UUID shareId, @Param("ownerId") UUID ownerId);

    @Query(value = """
            select t.id,t.description,t.venue_name as "venueName",c.name as "cityName",d.name as "districtName",
                   t.meeting_at as "meetingAt",t.expires_at as "expiresAt",t.status,
                   t.max_person_count as "maxPersonCount",
                   (select count(*) from tbl_table_group_participants p
                    where p.table_group_id=t.id and p.status='ACCEPTED') as "acceptedCount"
            from tbl_table_group t join tbl_city c on c.id=t.city_id
            left join tbl_district d on d.id=t.district_id
            where t.id in (:ids)
            """, nativeQuery = true)
    List<SourceCard> sourceCards(@Param("ids") Collection<UUID> ids);

    interface Visibility {
        UUID getProfileId();
        String getMode();
        boolean getChoiceCompleted();
    }

    interface SourceCard {
        UUID getId();
        String getDescription();
        String getVenueName();
        String getCityName();
        String getDistrictName();
        Instant getMeetingAt();
        Instant getExpiresAt();
        String getStatus();
        int getMaxPersonCount();
        long getAcceptedCount();
    }
}
