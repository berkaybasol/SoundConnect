package com.berkayb.soundconnect.modules.tablegroup.controller;

import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping(EndPoints.TableGroup.BASE)
@RequiredArgsConstructor
@Slf4j
public class TableGroupController {
	
	private final TableGroupService tableGroupService;
	private final UserRepository userRepository;
	
	/**
	 * Principal.username -> User -> UUID
	 * Bu helper controller içinde tekrar tekrar kullanılıyor.
	 */
	private UUID getCurrentUserId(Principal principal) {
		String username = principal.getName();
		return userRepository.findByUsername(username)
		                     .map(User::getId)
		                     .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
	}
	
	@Operation(summary = "Yeni masa olustur")
	@PostMapping
	public ResponseEntity<BaseResponse<TableGroupResponseDto>> createTableGroup(
			Principal principal,
			@Valid @RequestBody TableGroupCreateRequestDto requestDto
	) {
		UUID ownerId = getCurrentUserId(principal);
		
		TableGroupResponseDto dto = tableGroupService.createTableGroup(ownerId, requestDto);
		
		return ResponseEntity.status(201).body(
				BaseResponse.<TableGroupResponseDto>builder()
				            .success(true)
				            .code(201)
				            .message("Masa olusturuldu")
				            .data(dto)
				            .build()
		);
	}
	
	@Operation(summary = "Aktif masalari filtrele ve listele")
	@GetMapping(EndPoints.TableGroup.LIST_ACTIVE)
	public ResponseEntity<BaseResponse<Page<TableGroupResponseDto>>> listActiveTableGroups(
			@RequestParam UUID cityId,
			@RequestParam(required = false) UUID districtId,
			@RequestParam(required = false) UUID neighborhoodId,
			Pageable pageable
	) {
		Page<TableGroupResponseDto> page = tableGroupService.listActiveTableGroups(
				cityId,
				districtId,
				neighborhoodId,
				pageable
		);
		
		return ResponseEntity.ok(
				BaseResponse.<Page<TableGroupResponseDto>>builder()
				            .success(true)
				            .code(200)
				            .message("Aktif masalar listelendi")
				            .data(page)
				            .build()
		);
	}
	
	@Operation(summary = "Masa detayi getir")
	@GetMapping(EndPoints.TableGroup.DETAIL)
	public ResponseEntity<BaseResponse<TableGroupResponseDto>> getTableGroupDetail(
			@PathVariable UUID tableGroupId
	) {
		TableGroupResponseDto dto = tableGroupService.getTableGroupDetail(tableGroupId);
		
		return ResponseEntity.ok(
				BaseResponse.<TableGroupResponseDto>builder()
				            .success(true)
				            .code(200)
				            .message("Masa detayi getirildi")
				            .data(dto)
				            .build()
		);
	}
	
	@Operation(summary = "Masaya katilma istegi gonder")
	@PostMapping(EndPoints.TableGroup.JOIN)
	public ResponseEntity<BaseResponse<Void>> joinTableGroup(
			Principal principal,
			@PathVariable UUID tableGroupId
	) {
		UUID userId = getCurrentUserId(principal);
		
		tableGroupService.joinTableGroup(userId, tableGroupId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .code(200)
				            .message("Masaya katilim istegi gonderildi")
				            .build()
		);
	}
	
	@Operation(summary = "Owner katilimci istegini ONAYLAR")
	@PostMapping(EndPoints.TableGroup.APPROVE)
	public ResponseEntity<BaseResponse<Void>> approveJoinRequest(
			Principal principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID participantId
	) {
		UUID ownerId = getCurrentUserId(principal);
		
		tableGroupService.approveJoinRequest(ownerId, tableGroupId, participantId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .code(200)
				            .message("Katilim istegi onaylandi")
				            .build()
		);
	}
	
	@Operation(summary = "Owner katilimci istegini REDDEDER")
	@PostMapping(EndPoints.TableGroup.REJECT)
	public ResponseEntity<BaseResponse<Void>> rejectJoinRequest(
			Principal principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID participantId
	) {
		UUID ownerId = getCurrentUserId(principal);
		
		tableGroupService.rejectJoinRequest(ownerId, tableGroupId, participantId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .code(200)
				            .message("Katilim istegi reddedildi")
				            .build()
		);
	}
	
	@Operation(summary = "Kullanici masadan kendi ayrilir")
	@PostMapping(EndPoints.TableGroup.LEAVE)
	public ResponseEntity<BaseResponse<Void>> leaveTableGroup(
			Principal principal,
			@PathVariable UUID tableGroupId
	) {
		UUID userId = getCurrentUserId(principal);
		
		tableGroupService.leaveTableGroup(userId, tableGroupId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .code(200)
				            .message("Masadan ayrildiniz")
				            .build()
		);
	}
	
	@Operation(summary = "Owner bir kullaniciyi masadan atar")
	@PostMapping(EndPoints.TableGroup.KICK)
	public ResponseEntity<BaseResponse<Void>> removeParticipantFromTableGroup(
			Principal principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID participantId
	) {
		UUID ownerId = getCurrentUserId(principal);
		
		tableGroupService.removeParticipantFromTableGroup(ownerId, tableGroupId, participantId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .code(200)
				            .message("Kullanici masadan atildi")
				            .build()
		);
	}
	
	@Operation(summary = "Masa sahibi masayi iptal eder")
	@PostMapping(EndPoints.TableGroup.CANCEL)
	public ResponseEntity<BaseResponse<Void>> cancelTableGroup(
			Principal principal,
			@PathVariable UUID tableGroupId
	) {
		UUID ownerId = getCurrentUserId(principal);
		
		tableGroupService.cancelTableGroup(ownerId, tableGroupId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .code(200)
				            .message("Masa iptal edildi")
				            .build()
		);
	}
}