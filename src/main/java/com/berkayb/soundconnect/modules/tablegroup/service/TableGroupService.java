package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupVenueOptionDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface TableGroupService {
	
	// Yeni bir masa olusturur. ownerId'yi controller/dan alip parametre olarak gonderiyoruz
	TableGroupResponseDto createTableGroup(UUID ownerId, TableGroupCreateRequestDto requestDto);

	List<TableGroupVenueOptionDto> findVenueOptions(String query, int limit);
	
	// cityId null ise tum sehirlerdeki aktif masalari listeler. Alt lokasyon
	// filtreleri yalnizca ust lokasyonlariyla birlikte kullanilabilir.
	Page<TableGroupResponseDto> listActiveTableGroups(UUID viewerId, UUID cityId, UUID districtId,
	                                                  UUID neighborhoodId, Pageable pageable);

	// Kullanıcının sahibi veya kabul edilmiş katılımcısı olduğu aktif masalar.
	Page<TableGroupResponseDto> listMyActiveTableGroups(UUID viewerId, Pageable pageable);
	
	
	// tek bir masanin detayini getirir
	TableGroupResponseDto getTableGroupDetail(UUID viewerId, UUID tableGroupId);
	
	// masaya katilma istegi
	void joinTableGroup(UUID userId, UUID tableGroupId, String joinNote);
	
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
	
}
