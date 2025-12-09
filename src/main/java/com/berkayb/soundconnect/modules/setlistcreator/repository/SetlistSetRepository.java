package com.berkayb.soundconnect.modules.setlistcreator.repository;

import com.berkayb.soundconnect.modules.setlistcreator.entity.SetlistSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SetlistSetRepository extends JpaRepository<SetlistSet, UUID> {
	List<SetlistSet> findAllBySetlist_IdOrderByOrderNumberAsc(UUID setlistId);
}