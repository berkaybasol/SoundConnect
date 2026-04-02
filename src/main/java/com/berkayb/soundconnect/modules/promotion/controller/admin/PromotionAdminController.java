package com.berkayb.soundconnect.modules.promotion.controller.admin;

import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionSaveRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionUpdateRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.response.PromotionResponseDto;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;
import com.berkayb.soundconnect.modules.promotion.service.PromotionService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Promotion.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMINS / Promotion", description = "Admin promotion yönetim işlemleri")
public class PromotionAdminController {
	
	private final PromotionService promotionService;
	
	/**
	 * Yeni promotion kaydı oluşturur.
	 */
	@PostMapping(SAVE)
	public ResponseEntity<BaseResponse<PromotionResponseDto>> save(@RequestBody @Valid PromotionSaveRequestDto dto) {
		PromotionResponseDto response = promotionService.save(dto);
		return ResponseEntity.ok(
				BaseResponse.<PromotionResponseDto>builder()
				            .success(true)
				            .message("Promotion başarıyla oluşturuldu.")
				            .code(200)
							.data(response)
							.build()
		);
	}
	
	/**
	 * Mevcut promotion kaydını günceller.
	 */
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<PromotionResponseDto>> update(@PathVariable UUID id,
	                                                                 @RequestBody @Valid PromotionUpdateRequestDto dto) {
		PromotionResponseDto response = promotionService.update(id, dto);
		return ResponseEntity.ok(
				BaseResponse.<PromotionResponseDto>builder()
				            .success(true)
				            .message("Promotion başarıyla güncellendi.")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	/**
	 * Promotion kaydını id bilgisine göre getirir.
	 */
	@GetMapping(BY_ID)
	public ResponseEntity<BaseResponse<PromotionResponseDto>> getById(@PathVariable UUID id) {
		PromotionResponseDto response = promotionService.getById(id);
		return ResponseEntity.ok(
				BaseResponse.<PromotionResponseDto>builder()
				            .success(true)
				            .message("Promotion başarıyla getirildi.")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	/**
	 * Belirli bir placement alanındaki tüm promotion kayıtlarını getirir.
	 * Admin listeleme ekranları için kullanılır.
	 */
	@GetMapping(GET_ALL_BY_PLACEMENT)
	public ResponseEntity<BaseResponse<List<PromotionResponseDto>>> getAllByPlacement(@PathVariable PromotionPlacement placement) {
		List<PromotionResponseDto> response = promotionService.getAllByPlacement(placement);
		return ResponseEntity.ok(
				BaseResponse.<List<PromotionResponseDto>>builder()
				            .success(true)
				            .message("Placement alanına göre promotion kayıtları başarıyla getirildi.")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	/**
	 * Belirli bir status'e sahip promotion kayıtlarını getirir.
	 */
	@GetMapping(GET_ALL_BY_STATUS)
	public ResponseEntity<BaseResponse<List<PromotionResponseDto>>> getAllByStatus(@PathVariable PromotionStatus status) {
		List<PromotionResponseDto> response = promotionService.getAllByStatus(status);
		return ResponseEntity.ok(
				BaseResponse.<List<PromotionResponseDto>>builder()
				            .success(true)
				            .message("Status bilgisine göre promotion kayıtları başarıyla getirildi.")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	/**
	 * Belirli bir type'a sahip promotion kayıtlarını getirir.
	 */
	@GetMapping(GET_ALL_BY_TYPE)
	public ResponseEntity<BaseResponse<List<PromotionResponseDto>>> getAllByType(@PathVariable PromotionType type) {
		List<PromotionResponseDto> response = promotionService.getAllByType(type);
		return ResponseEntity.ok(
				BaseResponse.<List<PromotionResponseDto>>builder()
				            .success(true)
				            .message("Type bilgisine göre promotion kayıtları başarıyla getirildi.")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	/**
	 * Promotion kaydını siler.
	 */
	@DeleteMapping(DELETE)
	public ResponseEntity<BaseResponse<Void>> deleteById(@PathVariable UUID id) {
		promotionService.deleteById(id);
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("Promotion başarıyla silindi.")
				            .code(200)
				            .data(null)
				            .build()
		);
	}
}