package com.berkayb.soundconnect.modules.overthinking.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingFeedOrder;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostCommandService;
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
import org.springframework.security.access.prepost.PreAuthorize;
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
	private final OverthinkingPostCommandService commands;
	
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
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Post olustur")
	public ResponseEntity<BaseResponse<OverthinkingPostResponseDto>> createPost(
			Principal principal,
			@Valid @RequestBody OverthinkingPostSaveRequestDto dto
			) {
		UUID userId = getAuthenticatedUserId(principal);
		
		log.info("[OverthinkingPostController] Creating post by user {}", userId);
		
		var response = commands.create(userId, dto);
		
		return ResponseEntity.ok(BaseResponse.<OverthinkingPostResponseDto>builder()
				                         .success(true)
				                         .message("Post basariyla olusturuldu")
				                         .code(200)
				                         .data(response)
				                         .build());
	}
	
	@PutMapping(UPDATE)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Eski istemci uyumluluğu: paylaşılan yazılar düzenlenemez", deprecated = true)
	public ResponseEntity<BaseResponse<OverthinkingPostResponseDto>> updatePost(Principal principal, @PathVariable UUID postId, @Valid @RequestBody OverthinkingPostSaveRequestDto dto) {
		UUID userId = getAuthenticatedUserId(principal);
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
	@PreAuthorize("isAuthenticated()")
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
		
		var response = postService.getById(postId, userId);
		
		return ResponseEntity.ok(
				BaseResponse.<OverthinkingPostResponseDto>builder()
				            .success(true)
				            .message("Post başarıyla getirildi")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	@GetMapping(FEED)
	@Operation(summary = "Overthinking global feed")
	public ResponseEntity<BaseResponse<Page<OverthinkingPostResponseDto>>> getFeed(
			Principal principal,
			@ParameterObject Pageable pageable,
			@RequestParam(defaultValue = "NEWEST") OverthinkingFeedOrder order
	) {
		UUID viewerId = tryGetAuthenticatedUserId(principal);
		
		var response = postService.getAll(viewerId, pageable, order);
		
		return ResponseEntity.ok(BaseResponse.<Page<OverthinkingPostResponseDto>>builder()
		                                     .success(true)
		                                     .message("Feed başarıyla getirildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@GetMapping(MY_POSTS)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Giriş yapan kullanıcının postları")
	public ResponseEntity<BaseResponse<Page<OverthinkingPostResponseDto>>> getMyPosts(
			Principal principal,
			@ParameterObject Pageable pageable
	) {
		UUID userId = getAuthenticatedUserId(principal);
		
		var response = postService.getMyPosts(userId, pageable);
		
		return ResponseEntity.ok(BaseResponse.<Page<OverthinkingPostResponseDto>>builder()
		                                     .success(true)
		                                     .message("Postlarım başarıyla getirildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@GetMapping(BY_ARTIST)
	@Operation(summary = "Sanatçıya bağlı Overthinking postları")
	public ResponseEntity<BaseResponse<Page<OverthinkingPostResponseDto>>> getPostsByArtist(
			Principal principal,
			@PathVariable UUID artistId,
			@ParameterObject Pageable pageable
	) {
		UUID viewerId = tryGetAuthenticatedUserId(principal);
		
		var response = postService.getPostsByArtist(artistId, viewerId, pageable);
		
		return ResponseEntity.ok(BaseResponse.<Page<OverthinkingPostResponseDto>>builder()
		                                     .success(true)
		                                     .message("Sanatçı postları başarıyla getirildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
}
