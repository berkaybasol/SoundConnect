package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TableGroupService {
	
	// Yeni bir masa olusturur. ownerId'yi controller/dan alip parametre olarak gonderiyoruz
	TableGroupResponseDto createTableGroup(UUID ownerId, TableGroupCreateRequestDto requestDto);
	
	// Aktif masalari listeler
	Page<TableGroupResponseDto> listActiveTableGroups(Pageable pageable);
	
	
}