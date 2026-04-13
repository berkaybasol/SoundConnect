package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.publicapi;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileSearchItemDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ListenerProfile.*;

@RestController
@RequestMapping(PUBLIC_BASE)
@RequiredArgsConstructor
@Tag(name = "PUBLIC / Listener Profile", description = "Public listener profile endpoints")
public class ListenerProfilePublicController {
	
	private final ListenerProfileService listenerProfileService;
	
	@GetMapping(SEARCH)
	public ResponseEntity<BaseResponse<List<ListenerProfileSearchItemDto>>> search(
			@RequestParam(name = "q", required = false) String q
	) {
		List<ListenerProfileSearchItemDto> data = listenerProfileService.searchProfiles(q);
		return ResponseEntity.ok(
				BaseResponse.<List<ListenerProfileSearchItemDto>>builder()
				            .success(true)
				            .code(200)
				            .message("Listener profile search results")
				            .data(data)
				            .build()
		);
	}
	
	@GetMapping(PUBLIC_BY_PROFILE_ID)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> getByProfileId(
			@PathVariable UUID profileId) {
		
		ListenerProfileResponseDto response = listenerProfileService.getProfileByProfileId(profileId);
		
		return ResponseEntity.ok(
				BaseResponse.<ListenerProfileResponseDto>builder()
				            .success(true)
				            .code(200)
				            .message("Profil getirildi")
				            .data(response)
				            .build()
		);
	}
}