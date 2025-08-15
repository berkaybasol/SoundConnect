package com.berkayb.soundconnect.modules.message.dm.controller;


import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.service.DMMessageService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.DM.*;
@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
public class DMMessageController {
	private final DMMessageService dmMessageService;
	
	@GetMapping(MESSAGE_LIST)
	public ResponseEntity<BaseResponse<List<DMMessageResponseDto>>> getMessagesByConversationId(@PathVariable UUID conversationId) {
		List<DMMessageResponseDto> messages = dmMessageService.getMessagesByConversationId(conversationId);
		return ResponseEntity.ok(BaseResponse.<List<DMMessageResponseDto>> builder()
				                         .success(true)
				                         .message("mesajlar getirildi.")
				                         .code(200)
				                         .data(messages)
				                         .build());
	}
	
	// suan burdayim.
}