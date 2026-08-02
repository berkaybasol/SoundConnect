package com.berkayb.soundconnect.modules.message.dm.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMUnreadCountResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.service.DMMessageService;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(EndPoints.DM.USER_BASE)
@RequiredArgsConstructor
public class DMMessageUserController {
	
	private final DMMessageService messageService;
	private final DMConversationRepository conversationRepository;
	private final NotificationService notificationService;
	
	@GetMapping(EndPoints.DM.MESSAGE_LIST) // /messages/conversation/{conversationId}
	//@PreAuthorize("hasAuthority('READ_DM')")
	public ResponseEntity<BaseResponse<Page<DMMessageResponseDto>>> listByConversation(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable("conversationId") UUID conversationId,
			@ParameterObject @PageableDefault(size = 30, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		UUID currentUserId = principal.getId();
		ensureParticipant(conversationId, currentUserId);
		notificationService.markDmConversationAsRead(currentUserId, conversationId);
		
		Page<DMMessageResponseDto> data = messageService.getMessagesByConversationId(conversationId, pageable);
		return ResponseEntity.ok(BaseResponse.<Page<DMMessageResponseDto>>builder()
		                                     .success(true)
		                                     .message("Messages listed")
		                                     .code(200)
		                                     .data(data)
		                                     .build());
	}
	
	@PostMapping(EndPoints.DM.MESSAGE_SEND) // /messages
	//@PreAuthorize("hasAuthority('WRITE_DM')")
	public ResponseEntity<BaseResponse<DMMessageResponseDto>> send(
			@AuthenticationPrincipal UserDetailsImpl principal,
	                                                               @Valid @RequestBody DMMessageRequestDto request) {
		UUID currentUserId = principal.getId();
		// Service zaten sender'ın participant olup olmadığını kontrol ediyor.
		DMMessageResponseDto data = messageService.sendMessage(request, currentUserId);
		return ResponseEntity.ok(BaseResponse.<DMMessageResponseDto>builder()
		                                     .success(true)
		                                     .message("Message sent")
		                                     .code(200)
		                                     .data(data)
		                                     .build());
	}
	
	@PatchMapping(EndPoints.DM.MESSAGE_MARK_READ) // /messages/{messageId}/read
	//@PreAuthorize("hasAuthority('READ_DM')")
	public ResponseEntity<BaseResponse<Void>> markRead(
			@AuthenticationPrincipal UserDetailsImpl principal,
	                                                   @PathVariable("messageId") @NotNull UUID messageId) {
		UUID currentUserId = principal.getId();
		messageService.markMessageAsRead(messageId, currentUserId);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Message marked as read")
		                                     .code(200)
		                                     .data(null)
		                                     .build());
	}

	@GetMapping(EndPoints.DM.UNREAD_COUNT)
	public ResponseEntity<BaseResponse<DMUnreadCountResponseDto>> unreadCount(
			@AuthenticationPrincipal UserDetailsImpl principal
	) {
		UUID currentUserId = principal.getId();
		DMUnreadCountResponseDto data = new DMUnreadCountResponseDto(
				messageService.getUnreadCount(currentUserId));
		return ResponseEntity.ok(BaseResponse.<DMUnreadCountResponseDto>builder()
		                                     .success(true)
		                                     .message("Unread DM count retrieved")
		                                     .code(200)
		                                     .data(data)
		                                     .build());
	}
	private void ensureParticipant(UUID conversationId, UUID userId) {
		DMConversation conv = conversationRepository.findById(conversationId)
		                                            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
		if (!(userId.equals(conv.getUserAId()) || userId.equals(conv.getUserBId()))) {
			throw new SecurityException("User is not a participant of this conversation.");
		}
	}
}
