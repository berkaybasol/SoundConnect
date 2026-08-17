package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface CollabActorRepository extends JpaRepository<CollabActor, UUID> {
    Optional<CollabActor> findByProfileTypeAndSourceProfileId(ProfileType profileType, UUID sourceProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CollabActor a where a.profileType = :profileType and a.sourceProfileId = :sourceProfileId")
    Optional<CollabActor> findByProfileTypeAndSourceProfileIdForUpdate(
            @Param("profileType") ProfileType profileType,
            @Param("sourceProfileId") UUID sourceProfileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CollabActor a where a.id = :id")
    Optional<CollabActor> findByIdForUpdate(@Param("id") UUID id);
}
