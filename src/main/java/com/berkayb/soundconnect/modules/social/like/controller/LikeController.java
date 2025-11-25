package com.berkayb.soundconnect.modules.social.like.controller;

import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.social.like.service.LikeService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Like.*;


@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
public class LikeController {
	
	private final LikeService likeService;
	
	
	@PostMapping(LIKE)
	@Operation(summary ="bir icerigi begen (idempotent)")
	public ResponseEntity<BaseResponse<Void>> like(
			@AuthenticationPrincipal(expression = "id")UUID userId,
			@PathVariable EngagementTargetType targetType,
			@PathVariable UUID targetId) {
		log.info("[LikeController] User {} liked {}:{}", userId, targetType, targetId);
		
		likeService.like(userId, targetType, targetId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
						.success(true)
						.message("Begeni eklendi")
						.code(200)
						.build()
		);
	}
	
	@DeleteMapping(UNLIKE)
	@Operation(summary = "Begenemekten vazgec (idepotent)")
	public ResponseEntity<BaseResponse<Void>> unlike(
			@AuthenticationPrincipal(expression = "id")UUID userId,
			@PathVariable EngagementTargetType targetType,
			@PathVariable UUID targetId
	) {
		log.info("[LikeController] User {} unlikes {}:{}", userId, targetType, targetId);
		
		likeService.unlike(userId, targetType, targetId);
		
		return ResponseEntity.ok(BaseResponse.<Void>builder()
				                         .success(true)
				                         .message("Begeni kaldirildi")
				                         .code(200)
				                         .build());
	}
	
	@GetMapping(COUNT)
	public ResponseEntity<BaseResponse<Long>> countLikes(
			@PathVariable EngagementTargetType targetType,
			@PathVariable UUID targetId
	) {
		long count = likeService.countLikes(targetType, targetId);
		
		return ResponseEntity.ok(
				BaseResponse.<Long>builder()
						.success(true)
						.message("Begeni sayisi getirildi")
						.code(200)
						.data(count)
						.build()
		);
	}
	
	// flutterda toggle buton olarak kullanilcak
	@GetMapping(IS_LIKED)
	@Operation(summary = "Kullanici bu icerigi begenmis mi?")
	public ResponseEntity<BaseResponse<Boolean>> isLiked(
			@AuthenticationPrincipal(expression = "id")UUID userId,
			@PathVariable EngagementTargetType targetType,
			@PathVariable UUID targetId
	){
		boolean liked = likeService.isLiked(userId, targetType, targetId);
		
		return ResponseEntity.ok(
				BaseResponse.<Boolean>builder()
						.success(true)
						.message("Begeni durumu getirildi")
						.code(200)
						.data(liked)
						.build()
		);
	}
}