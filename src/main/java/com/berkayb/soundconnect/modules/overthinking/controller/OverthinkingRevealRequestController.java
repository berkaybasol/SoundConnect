package com.berkayb.soundconnect.modules.overthinking.controller;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPendingRevealRequestCountResponseDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingIncomingUnreadStatusResponseDto;
import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingIncomingSeenRequestDto;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingRevealInboxService;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingRevealRequestService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Overthinking.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
public class OverthinkingRevealRequestController {
	
	private final OverthinkingRevealRequestService revealRequestService;
	private final OverthinkingRevealInboxService inboxService;
	
	@PostMapping(CREATE_REVEAL_REQUEST)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Anonim post sahibini görmek için istek gönder")
	public ResponseEntity<BaseResponse<OverthinkingRevealRequestResponseDto>> createRevealRequest(
			@AuthenticationPrincipal(expression = "id") UUID requesterId,
			@PathVariable UUID postId
	) {
		var response = revealRequestService.createRevealRequest(requesterId, postId);
		
		return ResponseEntity.ok(BaseResponse.<OverthinkingRevealRequestResponseDto>builder()
		                                     .success(true)
		                                     .message("Görüntüleme isteği gönderildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@DeleteMapping(CREATE_REVEAL_REQUEST)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Bekleyen kimlik görüntüleme isteğimi geri çek")
	public ResponseEntity<BaseResponse<Void>> cancelRevealRequest(
			@AuthenticationPrincipal(expression = "id") UUID requesterId,
			@PathVariable UUID postId
	) {
		revealRequestService.cancelRevealRequest(requesterId, postId);
		return ResponseEntity.ok(BaseResponse.<Void>builder().success(true).code(200)
				.message("Görüntüleme isteği geri çekildi").build());
	}

	@PostMapping(APPROVE_REVEAL_REQUEST)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Görüntüleme isteğini kabul et")
	public ResponseEntity<BaseResponse<OverthinkingRevealRequestResponseDto>> approveRevealRequest(
			@AuthenticationPrincipal(expression = "id") UUID authorId,
			@PathVariable UUID requestId
	) {
		var response = revealRequestService.approveRevealRequest(authorId, requestId);
		
		return ResponseEntity.ok(BaseResponse.<OverthinkingRevealRequestResponseDto>builder()
		                                     .success(true)
		                                     .message("Görüntüleme isteği kabul edildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@PostMapping(REJECT_REVEAL_REQUEST)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Görüntüleme isteğini reddet")
	public ResponseEntity<BaseResponse<OverthinkingRevealRequestResponseDto>> rejectRevealRequest(
			@AuthenticationPrincipal(expression = "id") UUID authorId,
			@PathVariable UUID requestId
	) {
		var response = revealRequestService.rejectRevealRequest(authorId, requestId);
		
		return ResponseEntity.ok(BaseResponse.<OverthinkingRevealRequestResponseDto>builder()
		                                     .success(true)
		                                     .message("Görüntüleme isteği reddedildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@GetMapping(INCOMING_REVEAL_REQUESTS)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Anonim postlarıma gelen görüntüleme istekleri")
	public ResponseEntity<BaseResponse<Page<OverthinkingRevealRequestResponseDto>>> getIncomingRequests(
			@AuthenticationPrincipal(expression = "id") UUID authorId,
			@ParameterObject Pageable pageable
	) {
		var response = revealRequestService.getIncomingRequests(authorId, pageable);
		
		return ResponseEntity.ok(BaseResponse.<Page<OverthinkingRevealRequestResponseDto>>builder()
		                                     .success(true)
		                                     .message("Gelen görüntüleme istekleri listelendi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@GetMapping(INCOMING_PENDING_REVEAL_REQUEST_COUNT)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Karar bekleyen gelen görüntüleme isteklerinin sayısı")
	public ResponseEntity<BaseResponse<OverthinkingPendingRevealRequestCountResponseDto>> getIncomingPendingRequestCount(
			@AuthenticationPrincipal(expression = "id") UUID authorId
	) {
		var response = new OverthinkingPendingRevealRequestCountResponseDto(
				revealRequestService.getIncomingPendingRequestCount(authorId));
		return ResponseEntity.ok(BaseResponse.<OverthinkingPendingRevealRequestCountResponseDto>builder()
				.success(true)
				.message("Bekleyen görüntüleme isteği sayısı getirildi")
				.code(200)
				.data(response)
				.build());
	}

	@GetMapping(INCOMING_REVEAL_UNREAD_STATUS)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Gelen görüntüleme isteklerinin okunmamış durumu")
	public ResponseEntity<BaseResponse<OverthinkingIncomingUnreadStatusResponseDto>> getIncomingUnreadStatus(
			@AuthenticationPrincipal(expression = "id") UUID authorId
	) {
		return incomingStatus(inboxService.getUnreadStatus(authorId));
	}

	@PostMapping(INCOMING_REVEAL_SEEN)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Açılan gelen istekler kutusunu okundu olarak işaretle")
	public ResponseEntity<BaseResponse<OverthinkingIncomingUnreadStatusResponseDto>> markIncomingSeen(
			@AuthenticationPrincipal(expression = "id") UUID authorId,
			@Valid @RequestBody OverthinkingIncomingSeenRequestDto request
	) {
		return incomingStatus(inboxService.markSeen(authorId, request.revision()));
	}

	private ResponseEntity<BaseResponse<OverthinkingIncomingUnreadStatusResponseDto>> incomingStatus(
			OverthinkingIncomingUnreadStatusResponseDto status
	) {
		return ResponseEntity.ok(BaseResponse.<OverthinkingIncomingUnreadStatusResponseDto>builder()
				.success(true).code(200).message("Gelen isteklerin okunma durumu getirildi").data(status).build());
	}

	@GetMapping(SENT_REVEAL_REQUESTS)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Gönderdiğim görüntüleme istekleri")
	public ResponseEntity<BaseResponse<Page<OverthinkingRevealRequestResponseDto>>> getMySentRequests(
			@AuthenticationPrincipal(expression = "id") UUID requesterId,
			@ParameterObject Pageable pageable
	) {
		var response = revealRequestService.getMySentRequests(requesterId, pageable);
		
		return ResponseEntity.ok(BaseResponse.<Page<OverthinkingRevealRequestResponseDto>>builder()
		                                     .success(true)
		                                     .message("Gönderilen görüntüleme istekleri listelendi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
}
