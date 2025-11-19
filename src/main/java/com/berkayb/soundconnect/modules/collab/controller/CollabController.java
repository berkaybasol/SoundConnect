package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.security.Principal;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Collab.*;


@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
public class CollabController {
	
	private final CollabService collabService;
	
	
	@PostMapping(CREATE)
	@Operation(summary = "ilan olustur")
	public ResponseEntity<BaseResponse<CollabResponseDto>> createCollab(
			Principal principal,
			@Valid @RequestBody CollabCreateRequestDto dto
	) {
		UUID authenticatedUserId = UUID.fromString(principal.getName());
		log.info("[CollabController] Creating collab by user {}", authenticatedUserId);
		
		CollabResponseDto response = collabService.create(authenticatedUserId, dto);
		
		return ResponseEntity.ok(
				BaseResponse.<CollabResponseDto>builder()
						.success(true)
						.message("Ilan basariyla olusturuldu")
						.code(200)
						.data(response)
						.build()
		);
	}
	
	@PutMapping(UPDATE)
	@Operation(summary = "ilani guncelle")
	public ResponseEntity<BaseResponse<CollabResponseDto>> updateCollab(
			Principal principal,
			@PathVariable UUID collabId,
			@Valid @RequestBody CollabUpdateRequestDto dto
	) {
		UUID authenticatedUserId = UUID.fromString(principal.getName());
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
	@Operation(summary = "ilan sil")
	public ResponseEntity<BaseResponse<Void>> deleteCollab(
			Principal principal,
			@PathVariable UUID collabId
	) {
		UUID authenticatedUserId = UUID.fromString(principal.getName());
		log.info("[CollabController] Deleting collab {} by user {}", collabId, authenticatedUserId);
		
		collabService.delete(collabId, authenticatedUserId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
						.success(true)
						.message("Ilan basariyla silindi")
						.code(200)
						.data(null)
						.build()
		);
	}
	
	@GetMapping(BY_ID)
	@Operation(summary = "idye gore ilan getir")
	public ResponseEntity<BaseResponse<CollabResponseDto>> getById(
			Principal principal,
			@PathVariable UUID collabId
	){
		UUID authenticatedUserId = principal != null ? UUID.fromString(principal.getName()) : null;
		
		log.info("[CollabController] Fetching collab {}", collabId);
		
		CollabResponseDto dto = collabService.getById(collabId);
		
		return ResponseEntity.ok(
				BaseResponse.<CollabResponseDto>builder()
				            .success(true)
				            .message("ilan basariyla getirildi")
				            .code(200)
				            .data(dto)
				            .build()
		);
	}
	
	@GetMapping(SEARCH)
	@Operation(summary = "id ye gore ilan arama yap")
	@ResponseBody
	public ResponseEntity<BaseResponse<Page<CollabResponseDto>>> search(
			CollabFilterRequestDto filter,
			Pageable pageable
	) {
		log.info("[CollabController] Searching collabs with filter {}", filter);
		
		Page<CollabResponseDto> result = collabService.search(filter, pageable);
		
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