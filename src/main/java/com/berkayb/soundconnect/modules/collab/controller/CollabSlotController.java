package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabFillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUnfillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.service.CollabSlotManagementService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.security.Principal;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Collab.*;


@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(BASE)
public class CollabSlotController {
	
	private final CollabSlotManagementService slotService;
	
	@Operation(summary = " Owner collab ilanindaki bir slotu doldur")
	@PostMapping(FILL_SLOT)
	public ResponseEntity<BaseResponse<CollabResponseDto>> fillSlot(
			Principal principal,
			@PathVariable UUID collabId,
			@Valid @RequestBody CollabFillSlotRequestDto dto
	) {
		UUID authenticatedUserId = UUID.fromString(principal.getName());
		log.info("[CollabSlotController] Filling slot {} for collab {}", dto.instrumentId(), collabId);
		
		CollabResponseDto response = slotService.fill(authenticatedUserId, collabId, dto);
		
		return ResponseEntity.ok(
				BaseResponse.<CollabResponseDto>builder()
				            .success(true)
				            .message("Slot başarıyla dolduruldu")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	@PostMapping(UNFILL_SLOT)
	@Operation(summary = "Bir collab ilanindaki dolu olan enstruman slotunu bosalt")
	public ResponseEntity<BaseResponse<CollabResponseDto>> unfillSlot(
			Principal principal,
			@PathVariable UUID collabId,
			@Valid @RequestBody CollabUnfillSlotRequestDto dto
	) {
		UUID authenticatedUserId = UUID.fromString(principal.getName());
		log.info("[CollabSlotController] Unfilling slot {} for collab {}", dto.instrumentId(), collabId);
		
		CollabResponseDto response = slotService.unfill(authenticatedUserId, collabId, dto);
		
		return ResponseEntity.ok(
				BaseResponse.<CollabResponseDto>builder()
				            .success(true)
				            .message("Slot başarıyla boşaltıldı")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
}