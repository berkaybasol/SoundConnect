package com.berkayb.soundconnect.modules.social.comment.controller;

import com.berkayb.soundconnect.modules.social.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.social.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.social.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.social.comment.service.CommentService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Comment.*;
@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
public class CommentController {
	
	private final CommentService commentService;
	
	
	@PostMapping(CREATE)
	@Operation(summary = "Yorum olustur")
	public ResponseEntity<BaseResponse<CommentResponseDto>> createComment(
			@AuthenticationPrincipal(expression = "id")UUID userId,
			@PathVariable EngagementTargetType targetType,
			@PathVariable UUID targetId,
			@RequestBody CommentCreateRequestDto request
			) {
		CommentResponseDto result = commentService.createComment(userId, targetType, targetId, request);
		
		return ResponseEntity.ok(BaseResponse.<CommentResponseDto>builder()
				                         .success(true)
				                         .message("Yorum olusturuldu")
				                         .code(200)
				                         .data(result)
				                         .build());
	}
	
	@DeleteMapping(DELETE)
	@Operation(summary = "Yorum sil")
	public ResponseEntity<BaseResponse<Void>> deleteComment(
			@AuthenticationPrincipal(expression = "id") UUID userId,
			@PathVariable UUID commentId
	) {
		commentService.deleteComment(userId, commentId);
		
		return ResponseEntity.ok(BaseResponse.<Void>builder()
				                         .success(true)
				                         .message("Yorum basariyla silindi")
				                         .code(200)
				                         .build());
	}
	
	@GetMapping(LIST_BY_TARGET)
	@Operation(summary = "Yorumlari getir")
	public ResponseEntity<BaseResponse<Page<CommentResponseDto>>> getComments(
			@PathVariable EngagementTargetType targetType,
			@PathVariable UUID targetId,
			@ParameterObject Pageable pageable
	) {
		Page<CommentResponseDto> result = commentService.getComments(targetType, targetId, pageable);
		
		return ResponseEntity.ok(BaseResponse.<Page<CommentResponseDto>>builder()
				                         .success(true)
				                         .message("Yorumlar listelendi")
				                         .code(200)
				                         .data(result)
				                         .build());
	}
	
	@GetMapping(LIST_REPLIES)
	@Operation(summary = "Yanitlari getir")
	public ResponseEntity<BaseResponse<Page<CommentReplyResponseDto>>> getReplies(
			@PathVariable UUID commentId,
			@ParameterObject Pageable pageable
	) {
		Page<CommentReplyResponseDto> result = commentService.getReplies(commentId, pageable);
		
		return ResponseEntity.ok(BaseResponse.<Page<CommentReplyResponseDto>>builder()
				                         .success(true)
				                         .message("Yanitlar listelendi")
				                         .code(200)
				                         .data(result)
				                         .build());
	}
}