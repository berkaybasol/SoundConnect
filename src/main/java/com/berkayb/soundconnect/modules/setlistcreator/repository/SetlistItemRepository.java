package com.berkayb.soundconnect.modules.setlistcreator.repository;

import com.berkayb.soundconnect.modules.setlistcreator.entity.SetlistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SetlistItemRepository extends JpaRepository<SetlistItem, UUID> {
	List<SetlistItem> findAllBySet_IdOrderByOrderNumberAsc(UUID setId);
}