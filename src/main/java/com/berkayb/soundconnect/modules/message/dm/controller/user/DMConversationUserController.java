package com.berkayb.soundconnect.modules.message.dm.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.message.dm.service.DMConversationService;
import com.berkayb.soundconnect.modules.message.dm.service.DmConversationQueryService;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPageResponseDto;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(EndPoints.DM.USER_BASE)
@RequiredArgsConstructor
public class DMConversationUserController {
	
	private final DMConversationService conversationService;
	private final DmConversationQueryService conversationQueries;

	@GetMapping("/conversations/my/page")
	public ResponseEntity<BaseResponse<DMConversationPageResponseDto>> myConversationsPage(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@RequestParam(defaultValue = "30") int size,
			@RequestParam(required = false) String cursor) {
		return ResponseEntity.ok(BaseResponse.<DMConversationPageResponseDto>builder()
				.success(true).message("Conversations listed").code(200)
				.data(conversationQueries.page(principal.getId(), size, cursor)).build());
	}

	@GetMapping("/conversations/{conversationId}/preview")
	public ResponseEntity<BaseResponse<DMConversationPreviewResponseDto>> conversationPreview(
			@AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID conversationId) {
		return ResponseEntity.ok(BaseResponse.<DMConversationPreviewResponseDto>builder()
				.success(true).message("Conversation preview").code(200)
				.data(conversationQueries.preview(principal.getId(), conversationId)).build());
	}
	
	@GetMapping(EndPoints.DM.CONVERSATION_LIST) // /conversations/my
	//@PreAuthorize("hasAuthority('READ_DM')")
	public ResponseEntity<BaseResponse<List<DMConversationPreviewResponseDto>>> myConversations(
			@AuthenticationPrincipal UserDetailsImpl principal
	) {
		UUID currentUserId = principal.getId();
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
	public ResponseEntity<BaseResponse<UUID>> getOrCreateBetween(
			@AuthenticationPrincipal UserDetailsImpl principal,
	                                                             @RequestParam("otherUserId") @NotNull UUID otherUserId) {
		UUID currentUserId = principal.getId();
		UUID conversationId = conversationService.getOrCreateConversation(currentUserId, otherUserId);
		return ResponseEntity.ok(BaseResponse.<UUID>builder()
		                                     .success(true)
		                                     .message("Conversation ready")
		                                     .code(200)
		                                     .data(conversationId)
		                                     .build());
	}
}
