package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public class CollabController {
	
	private final CollabService collabService;
	
	/**
	 * Authenticated user ID alma—tek merkez
	 */
	private UUID getAuthenticatedUserId(Principal principal) {
		if (principal == null) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		
		if (principal instanceof UsernamePasswordAuthenticationToken token) {
			Object principalObj = token.getPrincipal();
			
			if (principalObj instanceof UserDetailsImpl details) {
				return details.getId(); // <<< USER ID BURADA
			}
		}
		
		throw new SoundConnectException(ErrorType.UNAUTHORIZED);
	}
	
	// AUTH OPTIONAL
	private UUID tryGetAuthenticatedUserId(Principal principal) {
		if (principal == null) return null;
		if (principal instanceof UsernamePasswordAuthenticationToken token) {
			Object principalObj = token.getPrincipal();
			if (principalObj instanceof UserDetailsImpl details) {
				return details.getId();
			}
		}
		return null;
	}
	
	@PostMapping(CREATE)
	@Operation(summary = "İlan oluştur")
	public ResponseEntity<BaseResponse<CollabResponseDto>> createCollab(
			Principal principal,
			@Valid @RequestBody CollabCreateRequestDto dto
	) {
		UUID authenticatedUserId = getAuthenticatedUserId(principal);
		
		log.info("[CollabController] Creating collab by user {}", authenticatedUserId);
		
		CollabResponseDto response = collabService.create(authenticatedUserId, dto);
		
		return ResponseEntity.ok(
				BaseResponse.<CollabResponseDto>builder()
				            .success(true)
				            .message("İlan başarıyla oluşturuldu")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	@PutMapping(UPDATE)
	@Operation(summary = "İlan güncelle")
	public ResponseEntity<BaseResponse<CollabResponseDto>> updateCollab(
			Principal principal,
			@PathVariable UUID collabId,
			@Valid @RequestBody CollabUpdateRequestDto dto
	) {
		UUID authenticatedUserId = getAuthenticatedUserId(principal);
		
		log.info("[CollabController] Updating collab {} by user {}", collabId, authenticatedUserId);
		
		CollabResponseDto response = collabService.update(collabId, authenticatedUserId, dto);
		
		return ResponseEntity.ok(
				BaseResponse.<CollabResponseDto>builder()
				            .success(true)
				            .message("İlan başarıyla güncellendi")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	@DeleteMapping(DELETE)
	@Operation(summary = "İlan sil")
	public ResponseEntity<BaseResponse<Void>> deleteCollab(
			Principal principal,
			@PathVariable UUID collabId
	) {
		UUID authenticatedUserId = getAuthenticatedUserId(principal);
		
		log.info("[CollabController] Deleting collab {} by user {}", collabId, authenticatedUserId);
		
		collabService.delete(collabId, authenticatedUserId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("İlan başarıyla silindi")
				            .code(200)
				            .data(null)
				            .build()
		);
	}
	
	@GetMapping(BY_ID)
	@Operation(summary = "ID’ye göre ilan getir")
	public ResponseEntity<BaseResponse<CollabResponseDto>> getById(
			Principal principal,
			@PathVariable UUID collabId
	) {
		UUID authenticatedUserId = tryGetAuthenticatedUserId(principal);
		
		log.info("[CollabController] Fetching collab {} by user {}", collabId, authenticatedUserId);
		
		CollabResponseDto response = collabService.getById(collabId, authenticatedUserId);
		
		return ResponseEntity.ok(
				BaseResponse.<CollabResponseDto>builder()
				            .success(true)
				            .message("İlan başarıyla getirildi")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	@GetMapping(SEARCH)
	@Operation(summary = "İlan arama")
	public ResponseEntity<BaseResponse<Page<CollabResponseDto>>> search(
			Principal principal,
			@ParameterObject CollabFilterRequestDto filter,
			@ParameterObject Pageable pageable
	) {
		UUID authenticatedUserId = tryGetAuthenticatedUserId(principal);
		
		log.info("[CollabController] Searching collabs with filter {} by user {}",
		         filter, authenticatedUserId);
		
		Page<CollabResponseDto> result = collabService.search(authenticatedUserId, filter, pageable);
		
		return ResponseEntity.ok(
				BaseResponse.<Page<CollabResponseDto>>builder()
				            .success(true)
				            .message("İlanlar başarıyla listelendi")
				            .code(200)
				            .data(result)
				            .build()
		);
	}
}