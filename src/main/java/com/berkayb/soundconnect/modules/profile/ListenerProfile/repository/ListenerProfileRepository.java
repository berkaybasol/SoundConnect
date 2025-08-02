package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ListenerProfileRepository extends JpaRepository<ListenerProfile, UUID> {
	Optional<ListenerProfile> findByUserId(UUID userId);
}