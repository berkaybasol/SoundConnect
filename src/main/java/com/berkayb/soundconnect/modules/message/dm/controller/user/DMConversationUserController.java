package com.berkayb.soundconnect.modules.message.dm.controller.user;

import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.message.dm.service.DMConversationService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
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
public class DMConversationUserController {
	
	private final DMConversationService conversationService;
	private final UserRepository userRepository;
	
	@GetMapping(EndPoints.DM.CONVERSATION_LIST) // /conversations/my
	//@PreAuthorize("hasAuthority('READ_DM')")
	public ResponseEntity<BaseResponse<List<DMConversationPreviewResponseDto>>> myConversations(Principal principal) {
		UUID currentUserId = currentUserId(principal);
		List<DMConversationPreviewResponseDto> data = conversationService.getAllConversationsForUser(currentUserId);
		return ResponseEntity.ok(BaseResponse.<List<DMConversationPreviewResponseDto>>builder()
		                                     .success(true)
		                                     .message("Conversations listed")
		                                     .code(200)
		                                     .data(data)
		                                     .build());
	}
	
	@PostMapping(EndPoints.DM.CONVERSATION_BETWEEN) // /conversations/between?otherUserId=...
	//@PreAuthorize("hasAuthority('WRITE_DM')")
	public ResponseEntity<BaseResponse<UUID>> getOrCreateBetween(Principal principal,
	                                                             @RequestParam("otherUserId") @NotNull UUID otherUserId) {
		UUID currentUserId = currentUserId(principal);
		UUID conversationId = conversationService.getOrCreateConversation(currentUserId, otherUserId);
		return ResponseEntity.ok(BaseResponse.<UUID>builder()
		                                     .success(true)
		                                     .message("Conversation ready")
		                                     .code(200)
		                                     .data(conversationId)
		                                     .build());
	}
	
	private UUID currentUserId(Principal principal) {
		String username = principal.getName();
		return userRepository.findByUsername(username)
		                     .map(User::getId)
		                     .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
	}
}