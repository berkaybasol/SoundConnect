package com.berkayb.soundconnect.modules.tablegroup.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupJoinRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupVenueOptionDto;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(EndPoints.TableGroup.BASE)
@RequiredArgsConstructor
@Slf4j
public class TableGroupController {
	
	private final TableGroupService tableGroupService;
	
	/**
	 * Authenticated principal already carries the immutable user UUID.
	 */
	private UUID getCurrentUserId(UserDetailsImpl principal) {
		return principal.getId();
	}
	
	@Operation(summary = "Yeni masa olustur")
	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<TableGroupResponseDto>> createTableGroup(
			@AuthenticationPrincipal UserDetailsImpl principal,
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

	@Operation(summary = "Masa olusturma icin kayitli mekan onerilerini ara")
	@GetMapping(EndPoints.TableGroup.VENUE_OPTIONS)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<List<TableGroupVenueOptionDto>>> findVenueOptions(
			@RequestParam(name = "q") String query,
			@RequestParam(name = "limit", defaultValue = "8") int limit
	) {
		List<TableGroupVenueOptionDto> options = tableGroupService.findVenueOptions(query, limit);
		return ResponseEntity.ok(
				BaseResponse.<List<TableGroupVenueOptionDto>>builder()
						.success(true)
						.code(200)
						.message("Kayitli mekan onerileri listelendi")
						.data(options)
						.build()
		);
	}
	
	@Operation(summary = "Aktif masalari filtrele ve listele")
	@GetMapping(EndPoints.TableGroup.LIST_ACTIVE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<Page<TableGroupResponseDto>>> listActiveTableGroups(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@RequestParam(required = false) UUID cityId,
			@RequestParam(required = false) UUID districtId,
			@RequestParam(required = false) UUID neighborhoodId,
			Pageable pageable
	) {
		Page<TableGroupResponseDto> page = tableGroupService.listActiveTableGroups(
				getCurrentUserId(principal),
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

	@Operation(summary = "Kullanicinin aktif masalarini listele")
	@GetMapping(EndPoints.TableGroup.LIST_MINE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<Page<TableGroupResponseDto>>> listMyActiveTableGroups(
			@AuthenticationPrincipal UserDetailsImpl principal,
			Pageable pageable
	) {
		Page<TableGroupResponseDto> page = tableGroupService.listMyActiveTableGroups(
				getCurrentUserId(principal),
				pageable
		);

		return ResponseEntity.ok(
				BaseResponse.<Page<TableGroupResponseDto>>builder()
						.success(true)
						.code(200)
						.message("Kullanicinin aktif masalari listelendi")
						.data(page)
						.build()
		);
	}
	
	@Operation(summary = "Masa detayi getir")
	@GetMapping(EndPoints.TableGroup.DETAIL)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<TableGroupResponseDto>> getTableGroupDetail(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId
	) {
		TableGroupResponseDto dto = tableGroupService.getTableGroupDetail(getCurrentUserId(principal), tableGroupId);
		
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
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<Void>> joinTableGroup(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId,
			@Valid @RequestBody(required = false) TableGroupJoinRequestDto dto
	) {
		UUID userId = getCurrentUserId(principal);
		String joinNote = dto == null ? null : dto.note();
		tableGroupService.joinTableGroup(userId, tableGroupId, joinNote);
		
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
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<Void>> approveJoinRequest(
			@AuthenticationPrincipal UserDetailsImpl principal,
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
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<Void>> rejectJoinRequest(
			@AuthenticationPrincipal UserDetailsImpl principal,
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
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<Void>> leaveTableGroup(
			@AuthenticationPrincipal UserDetailsImpl principal,
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
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<Void>> removeParticipantFromTableGroup(
			@AuthenticationPrincipal UserDetailsImpl principal,
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
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<Void>> cancelTableGroup(
			@AuthenticationPrincipal UserDetailsImpl principal,
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
