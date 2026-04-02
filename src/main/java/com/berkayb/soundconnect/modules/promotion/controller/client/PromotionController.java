package com.berkayb.soundconnect.modules.promotion.controller.client;

import com.berkayb.soundconnect.modules.promotion.dto.response.PromotionResponseDto;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.service.PromotionService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Promotion.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Promotion", description = "Client tarafında gösterilecek promotion işlemleri")
public class PromotionController {
	
	private final PromotionService promotionService;
	
	/**
	 * İlgili placement alanında şu an gösterime uygun promotion kayıtlarını getirir.
	 *
	 * Kurallar:
	 * - sadece ACTIVE olanlar
	 * - tarih aralığı uygunsa gösterilir
	 */
	@GetMapping(GET_DISPLAYABLE_BY_PLACEMENT)
	public ResponseEntity<BaseResponse<List<PromotionResponseDto>>> getDisplayableByPlacement(
			@PathVariable PromotionPlacement placement) {
		
		List<PromotionResponseDto> response = promotionService.getDisplayableByPlacement(placement);
		
		return ResponseEntity.ok(
				BaseResponse.<List<PromotionResponseDto>>builder()
				            .success(true)
				            .message("Gösterime uygun promotion kayıtları başarıyla getirildi.")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
}