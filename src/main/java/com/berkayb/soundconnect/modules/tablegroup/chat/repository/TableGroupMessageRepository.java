package com.berkayb.soundconnect.modules.tablegroup.chat.repository;

import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TableGroupMessageRepository extends JpaRepository<TableGroupMessage, UUID> {
	
	// belirli bir masaya ait aktif mesajlari olusturma zamanina gore ascending seklinde getirir
	Page<TableGroupMessage> findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID tableGroupId, Pageable pageable);
}