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
	Page<TableGroupResponseDto> listActiveTableGroups(UUID cityId, UUID districtId,
	                                                  UUID neighborhoodId, Pageable pageable);
	
	
	// tek bir masanin detayini getirir
	TableGroupResponseDto getTableGroupDetail(UUID tableGroupId);
	
	// masaya katilma istegi
	void joinTableGroup(UUID userId, UUID tableGroupId);
	
	// masa sahibi katilim istegini onaylar
	void approveJoinRequest(UUID ownerId, UUID tableGroupId, UUID participantId);
	
	// masa sahibi katilim istegini reddeder
	void rejectJoinRequest(UUID ownerId, UUID tableGroupId, UUID participantId);
	
	// katilimci masadan kendisi ayrilir
	void leaveTableGroup(UUID userId, UUID tableGroupId);
	
	// masa sahibi birini masadan atar
	void removeParticipantFromTableGroup(UUID ownerId, UUID tableGroupId, UUID participantId);
	
	// masa sahibi masayi iptal etsin
	void cancelTableGroup(UUID ownerId, UUID tableGroupId);
	
	//TODO masaya katilim/istek notification, update/delete vs methodlar eklencek
}