package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BandMemberRepository extends JpaRepository<BandMember, UUID> {
	
	// ayni kullanici bir bandin icinde yalnizca bir kere bulunmali.
	// band ve user idsine gore uyelik bulmak icin
	Optional<BandMember> findByBandIdAndUserId(UUID bandId, UUID userId);
	
	
	// belirli bir bandin butun uyelerini getirir.
	List<BandMember> findByBandId(UUID bandId);
	
	// belirli bir kullanicin tum band uyeliklerini getirir
	List<BandMember> findByUserId(UUID userId);
	
	List<BandMember> findByUserIdAndStatus(UUID userId, BandMemberShipStatus status);
	
}