package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUnfillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.service.CollabSlotManagementService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Collab.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(BASE)
public class CollabSlotController {
	
	private final CollabSlotManagementService slotService;
	
	
	private UUID getAuthenticatedUserId(Principal principal) {
		if (principal == null) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		
		if (principal instanceof UsernamePasswordAuthenticationToken token) {
			Object principalObj = token.getPrincipal();
			
			if (principalObj instanceof UserDetailsImpl userDetails) {
				return userDetails.getId();  // <<< KRİTİK NOKTA BURASI
			}
		}
		
		throw new SoundConnectException(ErrorType.UNAUTHORIZED);
	}
	
	
	@PostMapping(FILL_SLOT)
	@Operation(summary = "Owner collab ilanındaki slotu doldur")
	public ResponseEntity<BaseResponse<CollabResponseDto>> fillSlot(
			Principal principal,
			@PathVariable UUID collabId,
			@Valid @RequestBody CollabFillSlotRequestDto dto
	) {
		UUID authenticatedUserId = getAuthenticatedUserId(principal);
		log.info("[CollabSlotController] Filling slot {} in collab {} by user {}",
		         dto.instrumentId(), collabId, authenticatedUserId);
		
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
	@Operation(summary = "Owner collab ilanındaki slotu boşalt")
	public ResponseEntity<BaseResponse<CollabResponseDto>> unfillSlot(
			Principal principal,
			@PathVariable UUID collabId,
			@Valid @RequestBody CollabUnfillSlotRequestDto dto
	) {
		UUID authenticatedUserId = getAuthenticatedUserId(principal);
		log.info("[CollabSlotController] Unfilling slot {} in collab {} by user {}",
		         dto.instrumentId(), collabId, authenticatedUserId);
		
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