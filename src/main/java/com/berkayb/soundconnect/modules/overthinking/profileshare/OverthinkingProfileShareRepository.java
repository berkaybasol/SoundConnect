package com.berkayb.soundconnect.modules.overthinking.profileshare;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface OverthinkingProfileShareRepository extends JpaRepository<OverthinkingProfileShare, UUID> {
    Optional<OverthinkingProfileShare> findByOwnerUserIdAndSourcePostId(UUID ownerUserId, UUID sourcePostId);
    Optional<OverthinkingProfileShare> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);
    Page<OverthinkingProfileShare> findByListenerProfileIdAndOwnerUserId(UUID listenerProfileId, UUID ownerUserId, Pageable pageable);

    @Query("select share.sourcePostId from OverthinkingProfileShare share where share.id=:shareId and share.ownerUserId=:ownerId")
    Optional<UUID> ownedSourceId(@Param("shareId") UUID shareId, @Param("ownerId") UUID ownerId);
    @Query(value = "select id from tbl_user where id=:userId and status='ACTIVE' and email_verified for update", nativeQuery = true)
    Optional<UUID> lockActor(@Param("userId") UUID userId);
    @Query(value = "select id from tbl_user where id=:userId and status='ACTIVE' and email_verified for share", nativeQuery = true)
    Optional<UUID> lockActiveReader(@Param("userId") UUID userId);
    @Query(value = "select user_id from \"tbl_listener-profile\" where id=:profileId", nativeQuery = true)
    Optional<UUID> listenerOwner(@Param("profileId") UUID profileId);
    @Query(value = """
            select id as "profileId", visibility_mode as "mode", visibility_choice_completed as "choiceCompleted"
            from "tbl_listener-profile" where user_id=:userId for share
            """, nativeQuery = true)
    Optional<Visibility> lockVisibility(@Param("userId") UUID userId);
    @Query(value = "select id from tbl_overthinking_post where id=:postId for share", nativeQuery = true)
    Optional<UUID> lockSource(@Param("postId") UUID postId);
    interface Visibility { UUID getProfileId(); String getMode(); boolean getChoiceCompleted(); }
}
