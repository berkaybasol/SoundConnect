package com.berkayb.soundconnect.modules.message.dm.controller.admin;

import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping(EndPoints.DM.ADMIN_BASE)
@RequiredArgsConstructor
public class DMAdminController {
	
	private final DMConversationRepository conversationRepository;
	private final DMMessageRepository messageRepository;
	
	// GET /api/v1/admin/dm/conversations
	@GetMapping(EndPoints.DM.ADMIN_CONVERSATIONS)
	//@PreAuthorize("hasAuthority('MANAGE_DM')")
	public ResponseEntity<BaseResponse<List<DMConversationPreviewResponseDto>>> getAllConversations() {
		List<DMConversationPreviewResponseDto> data = conversationRepository.findAll()
		                                                                    .stream()
		                                                                    .map(this::toAdminPreview)
		                                                                    .sorted(Comparator.comparing(DMConversationPreviewResponseDto::lastMessageAt,
		                                                                                                 Comparator.nullsLast(Comparator.reverseOrder())))
		                                                                    .collect(Collectors.toList());
		
		return ResponseEntity.ok(BaseResponse.<List<DMConversationPreviewResponseDto>>builder()
		                                     .success(true)
		                                     .message("All conversations listed")
		                                     .code(200)
		                                     .data(data)
		                                     .build());
	}
	
	// GET /api/v1/admin/dm/{conversationId}
	@GetMapping(EndPoints.DM.ADMIN_CONVERSATION_BY_ID)
	//@PreAuthorize("hasAuthority('MANAGE_DM')")
	public ResponseEntity<BaseResponse<DMConversation>> getConversationById(@PathVariable UUID conversationId) {
		DMConversation conv = conversationRepository.findById(conversationId)
		                                            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
		
		return ResponseEntity.ok(BaseResponse.<DMConversation>builder()
		                                     .success(true)
		                                     .message("Conversation detail")
		                                     .code(200)
		                                     .data(conv)
		                                     .build());
	}
	
	// DELETE /api/v1/admin/dm/{conversationId}
	@DeleteMapping(EndPoints.DM.ADMIN_CONVERSATION_BY_ID)
	//@PreAuthorize("hasAuthority('MANAGE_DM')")
	public ResponseEntity<BaseResponse<Void>> deleteConversation(@PathVariable UUID conversationId) {
		if (!conversationRepository.existsById(conversationId)) {
			throw new IllegalArgumentException("Conversation not found: " + conversationId);
		}
		// Not: İleride soft delete düşünürsen DMConversation'a deletedAt ekleyebilirsin.
		conversationRepository.deleteById(conversationId);
		
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Conversation deleted")
		                                     .code(200)
		                                     .data(null)
		                                     .build());
	}
	
	// GET /api/v1/admin/dm/messages?conversationId=...
	@GetMapping(EndPoints.DM.ADMIN_MESSAGES)
	//@PreAuthorize("hasAuthority('MANAGE_DM')")
	public ResponseEntity<BaseResponse<List<DMMessageResponseDto>>> getMessages(@RequestParam UUID conversationId) {
		// varsa yoksa kontrolü
		conversationRepository.findById(conversationId)
		                      .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
		
		List<DMMessageResponseDto> data = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
		                                                   .stream()
		                                                   .map(this::toMessageDto)
		                                                   .collect(Collectors.toList());
		
		return ResponseEntity.ok(BaseResponse.<List<DMMessageResponseDto>>builder()
		                                     .success(true)
		                                     .message("Messages listed")
		                                     .code(200)
		                                     .data(data)
		                                     .build());
	}
	
	// DELETE /api/v1/admin/dm/{conversationId}/messages/{messageId}
	@DeleteMapping(EndPoints.DM.ADMIN_DELETE_MESSAGE)
	//@PreAuthorize("hasAuthority('MANAGE_DM')")
	public ResponseEntity<BaseResponse<Void>> deleteMessage(@PathVariable UUID conversationId,
	                                                        @PathVariable UUID messageId) {
		DMMessage msg = messageRepository.findById(messageId)
		                                 .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
		
		if (!msg.getConversationId().equals(conversationId)) {
			throw new IllegalArgumentException("Message does not belong to this conversation");
		}
		
		// Not: İleride soft delete istersen msg.setDeletedAt(LocalDateTime.now()) ile güncelleyebilirsin.
		messageRepository.delete(msg);
		
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Message deleted")
		                                     .code(200)
		                                     .data(null)
		                                     .build());
	}
	
	// --- Helpers ---
	
	// Admin perspektifinde preview: UserA'yı "other" gibi gösteriyoruz (admin'e özel DTO açılana kadar).
	private DMConversationPreviewResponseDto toAdminPreview(DMConversation conv) {
		var lastMsgOpt = messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conv.getId());
		
		String lastContent = lastMsgOpt.map(DMMessage::getContent).orElse(null);
		String lastType = lastMsgOpt.map(DMMessage::getMessageType).orElse(null);
		UUID lastSenderId = lastMsgOpt.map(DMMessage::getSenderId).orElse(null);
		LocalDateTime lastAt = lastMsgOpt.map(DMMessage::getCreatedAt).orElse(conv.getLastMessageAt());
		Boolean lastRead = lastMsgOpt.map(m -> m.getReadAt() != null).orElse(null);
		
		return new DMConversationPreviewResponseDto(
				conv.getId(),
				conv.getUserAId(),          // otherUserId (geçici)
				"UserA",                    // otherUsername (geçici)
				null,                       // otherUserProfilePicture (geçici)
				lastContent,
				lastType,
				lastSenderId,
				lastAt,
				lastRead
		);
	}
	
	private DMMessageResponseDto toMessageDto(DMMessage msg) {
		return new DMMessageResponseDto(
				msg.getId(),
				msg.getConversationId(),
				msg.getSenderId(),
				msg.getRecipientId(),
				msg.getContent(),
				msg.getMessageType(),
				msg.getCreatedAt(),
				msg.getReadAt(),
				msg.getDeletedAt()
		);
	}
}