package com.berkayb.soundconnect.modules.overthinking.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
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
import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Overthinking.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
public class OverthinkingPostController {
	
	private final OverthinkingPostService postService;
	
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
				return details.getId();
			}
		}
		
		throw new SoundConnectException(ErrorType.UNAUTHORIZED);
	}
	
	/**
	 * Auth optional (feed gibi yerlerde)
	 */
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
	@Operation(summary = "Post olustur")
	public ResponseEntity<BaseResponse<OverthinkingPostResponseDto>> createPost(
			Principal principal,
			@Valid @RequestBody OverthinkingPostSaveRequestDto dto
			) {
		UUID userId = getAuthenticatedUserId(principal);
		
		log.info("[OverthinkingPostController] Creating post by user {}", userId);
		
		var response = postService.create(userId, dto);
		
		return ResponseEntity.ok(BaseResponse.<OverthinkingPostResponseDto>builder()
				                         .success(true)
				                         .message("Post basariyla olusturuldu")
				                         .code(200)
				                         .data(response)
				                         .build());
	}
	
	@PutMapping(UPDATE)
	@Operation(summary = "Post guncelle")
	public ResponseEntity<BaseResponse<OverthinkingPostResponseDto>> updatePost(Principal principal, @PathVariable UUID postId, @Valid @RequestBody OverthinkingPostSaveRequestDto dto) {
		UUID userId = getAuthenticatedUserId(principal);
		log.info("[Overthinking] Updating post {} by user {}", postId, userId);
		var response = postService.update(postId, userId, dto);
		return ResponseEntity.ok(
				BaseResponse.<OverthinkingPostResponseDto>builder()
				            .success(true)
				            .message("Post başarıyla güncellendi")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	
	@DeleteMapping(DELETE)
	@Operation(summary = "Overthinking post sil")
	public ResponseEntity<BaseResponse<Void>> deletePost(
			Principal principal,
			@PathVariable UUID postId
	) {
		UUID userId = getAuthenticatedUserId(principal);
		
		log.info("[Overthinking] Deleting post {} by user {}", postId, userId);
		
		postService.delete(postId, userId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("Post başarıyla silindi")
				            .code(200)
				            .data(null)
				            .build()
		);
	}
	
	@GetMapping(BY_ID)
	@Operation(summary = "ID’ye göre post getir")
	public ResponseEntity<BaseResponse<OverthinkingPostResponseDto>> getById(
			Principal principal,
			@PathVariable UUID postId
	) {
		UUID userId = tryGetAuthenticatedUserId(principal);
		
		log.info("[Overthinking] Fetching post {} by user {}", postId, userId);
		
		var response = postService.getById(postId);
		
		return ResponseEntity.ok(
				BaseResponse.<OverthinkingPostResponseDto>builder()
				            .success(true)
				            .message("Post başarıyla getirildi")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
}