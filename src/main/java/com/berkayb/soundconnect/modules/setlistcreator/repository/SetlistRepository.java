package com.berkayb.soundconnect.modules.setlistcreator.repository;

import com.berkayb.soundconnect.modules.setlistcreator.entity.Setlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SetlistRepository extends JpaRepository<Setlist, UUID> {
	List<Setlist> findAllByMusicianProfile_Id(UUID musicianProfileId);
	
	List<Setlist> findAllByBand_Id(UUID bandId);
}