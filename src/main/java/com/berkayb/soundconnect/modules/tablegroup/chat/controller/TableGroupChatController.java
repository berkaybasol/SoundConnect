package com.berkayb.soundconnect.modules.tablegroup.chat.controller;

import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.request.TableGroupMessageRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.service.TableGroupChatService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

/**
 * Masa (TableGroup) icindeki grup sohbetini yoneten user-facing controller.
 *
 * Kurallar:
 * - Sadece ACCEPTED status'lu katilimcilar erisebilir.
 * - POST yeni mesaj olusturur, DB'ye yazar, WS ile yayinlar.
 * - GET geçmiş mesajlari pageable dondurur.
 */
@RestController
@RequestMapping(EndPoints.TableGroup.Chat.BASE)
@RequiredArgsConstructor
@Slf4j
public class TableGroupChatController {
	
	private final TableGroupChatService chatService;
	private final UserRepository userRepository;
	private final TableGroupChatUnreadHelper unreadHelper;
	
	/**
	 * Principal.username -> User -> UUID
	 * Güvenilir userId elde etme helper'ı.
	 */
	private UUID currentUserId(Principal principal) {
		String username = principal.getName();
		return userRepository.findByUsername(username)
		                     .map(User::getId)
		                     .orElseThrow(() ->
				                                  new IllegalStateException("Authenticated user not found: " + username)
		                     );
	}
	
	/**
	 * GET /api/v1/table-groups/{tableGroupId}/chat/messages
	 *
	 * Query params: ?page=0&size=20
	 */
	@GetMapping(EndPoints.TableGroup.Chat.MESSAGES)
	// @PreAuthorize("hasAuthority('READ_TABLE_GROUP_CHAT')")
	public ResponseEntity<BaseResponse<Page<TableGroupMessageResponseDto>>> getMessages(
			Principal principal,
			@PathVariable("tableGroupId") UUID tableGroupId,
			Pageable pageable
	) {
		UUID requesterId = currentUserId(principal);
		
		Page<TableGroupMessageResponseDto> page = chatService.getMessages(
				requesterId,
				tableGroupId,
				pageable
		);
		
		return ResponseEntity.ok(
				BaseResponse.<Page<TableGroupMessageResponseDto>>builder()
				            .success(true)
				            .message("Table group chat messages listed")
				            .code(200)
				            .data(page)
				            .build()
		);
	}
	
	/**
	 * POST /api/v1/table-groups/{tableGroupId}/chat/messages
	 *
	 * Body:
	 * {
	 *   "content": "kanka nerdesiniz",
	 *   "messageType": "TEXT"
	 * }
	 */
	@PostMapping(EndPoints.TableGroup.Chat.MESSAGES)
	// @PreAuthorize("hasAuthority('WRITE_TABLE_GROUP_CHAT')")
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> sendMessage(
			Principal principal,
			@PathVariable("tableGroupId") UUID tableGroupId,
			@Valid @RequestBody TableGroupMessageRequestDto requestDto
	) {
		UUID senderId = currentUserId(principal);
		
		TableGroupMessageResponseDto dto = chatService.sendMessage(
				senderId,
				tableGroupId,
				requestDto
		);
		
		return ResponseEntity.ok(
				BaseResponse.<TableGroupMessageResponseDto>builder()
				            .success(true)
				            .message("Message sent")
				            .code(200)
				            .data(dto)
				            .build()
		);
	}
	
	/**
	 * GET /api/v1/table-groups/{tableGroupId}/chat/unread
	 *
	 * - Bu endpoint sadece authenticated kullanıcının kendi unread badge'ini döndürür.
	 * - userId dışardan parametre olarak ALINMAZ çünkü o güvenlik açığı olur.
	 * - unreadHelper.resetUnread() burada yapılmaz; sadece okumak için.
	 */
	@GetMapping(EndPoints.TableGroup.Chat.GET_UNREAD_BADGE)
	public ResponseEntity<BaseResponse<Integer>> getUnreadBadge(
			Principal principal,
			@PathVariable("tableGroupId") UUID tableGroupId
	) {
		UUID userId = currentUserId(principal);
		
		int unread = unreadHelper.getUnread(userId, tableGroupId);
		
		return ResponseEntity.ok(
				BaseResponse.<Integer>builder()
				            .success(true)
				            .message("Unread badge fetched")
				            .code(200)
				            .data(unread)
				            .build()
		);
	}
}