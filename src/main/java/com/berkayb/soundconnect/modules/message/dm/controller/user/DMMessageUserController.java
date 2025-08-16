package com.berkayb.soundconnect.modules.message.dm.controller.user;

import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.service.DMMessageService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(EndPoints.DM.USER_BASE)
@RequiredArgsConstructor
public class DMMessageUserController {
	
	private final DMMessageService messageService;
	private final DMConversationRepository conversationRepository;
	private final UserRepository userRepository;
	
	@GetMapping(EndPoints.DM.MESSAGE_LIST) // /messages/conversation/{conversationId}
	//@PreAuthorize("hasAuthority('READ_DM')")
	public ResponseEntity<BaseResponse<List<DMMessageResponseDto>>> listByConversation(Principal principal,
	                                                                                   @PathVariable("conversationId") UUID conversationId) {
		UUID currentUserId = currentUserId(principal);
		ensureParticipant(conversationId, currentUserId);
		
		List<DMMessageResponseDto> data = messageService.getMessagesByConversationId(conversationId);
		return ResponseEntity.ok(BaseResponse.<List<DMMessageResponseDto>>builder()
		                                     .success(true)
		                                     .message("Messages listed")
		                                     .code(200)
		                                     .data(data)
		                                     .build());
	}
	
	@PostMapping(EndPoints.DM.MESSAGE_SEND) // /messages
	//@PreAuthorize("hasAuthority('WRITE_DM')")
	public ResponseEntity<BaseResponse<DMMessageResponseDto>> send(Principal principal,
	                                                               @Valid @RequestBody DMMessageRequestDto request) {
		UUID currentUserId = currentUserId(principal);
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
	public ResponseEntity<BaseResponse<Void>> markRead(Principal principal,
	                                                   @PathVariable("messageId") @NotNull UUID messageId) {
		UUID currentUserId = currentUserId(principal);
		messageService.markMessageAsRead(messageId, currentUserId);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Message marked as read")
		                                     .code(200)
		                                     .data(null)
		                                     .build());
	}
	
	private UUID currentUserId(Principal principal) {
		String username = principal.getName();
		return userRepository.findByUsername(username)
		                     .map(User::getId)
		                     .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
	}
	
	private void ensureParticipant(UUID conversationId, UUID userId) {
		DMConversation conv = conversationRepository.findById(conversationId)
		                                            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
		if (!(userId.equals(conv.getUserAId()) || userId.equals(conv.getUserBId()))) {
			throw new SecurityException("User is not a participant of this conversation.");
		}
	}
}