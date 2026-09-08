package com.berkayb.soundconnect.modules.like.controller;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.service.LikeService;
import com.berkayb.soundconnect.modules.like.dto.CommentLikeState;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
	@PreAuthorize("isAuthenticated()")
	@Operation(summary ="bir icerigi begen (idempotent)")
	public ResponseEntity<BaseResponse<Object>> like(
			@AuthenticationPrincipal(expression = "id")UUID userId,
			@PathVariable EngagementTargetType targetType,
			@PathVariable UUID targetId) {
		log.info("[LikeController] User {} liked {}:{}", userId, targetType, targetId);
		
		Object result=null;
		if(targetType==EngagementTargetType.COMMENT) result=likeService.setCommentLike(userId,targetId,true);
		else likeService.like(userId, targetType, targetId);
		
		return ResponseEntity.ok(
				BaseResponse.<Object>builder()
						.success(true)
						.message("Begeni eklendi")
						.code(200)
						.data(result)
						.build()
		);
	}
	
	@DeleteMapping(UNLIKE)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Begenemekten vazgec (idepotent)")
	public ResponseEntity<BaseResponse<Object>> unlike(
			@AuthenticationPrincipal(expression = "id")UUID userId,
			@PathVariable EngagementTargetType targetType,
			@PathVariable UUID targetId
	) {
		log.info("[LikeController] User {} unlikes {}:{}", userId, targetType, targetId);
		
		Object result=null;
		if(targetType==EngagementTargetType.COMMENT) result=likeService.setCommentLike(userId,targetId,false);
		else likeService.unlike(userId, targetType, targetId);
		
		return ResponseEntity.ok(BaseResponse.<Object>builder()
				                         .success(true)
				                         .message("Begeni kaldirildi")
				                         .code(200)
				                         .data(result)
				                         .build());
	}

	@GetMapping("/COMMENT/{commentId}/state")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BaseResponse<CommentLikeState>> commentState(
			@AuthenticationPrincipal(expression = "id") UUID userId,@PathVariable UUID commentId) {
		return ResponseEntity.ok(BaseResponse.<CommentLikeState>builder().success(true).code(200)
				.message("Yorum beğeni durumu getirildi").data(likeService.readCommentLike(userId,commentId)).build());
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
	@PreAuthorize("isAuthenticated()")
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
