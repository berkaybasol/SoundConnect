package com.berkayb.soundconnect.modules.profile.shared.resolver.controller.publicapi;

import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(EndPoints.PROFILE_RESOLVER.PUBLIC_BASE)
@RequiredArgsConstructor
public class PublicProfileResolverController {
	
	private final PublicProfileResolverService resolverService;
	
	@GetMapping(EndPoints.PROFILE_RESOLVER.BY_USER)
	public ResponseEntity<BaseResponse<UserProfilesResolveResponseDto>> resolveByUser(
			@PathVariable UUID userId
	) {
		UserProfilesResolveResponseDto data = resolverService.resolveByUserId(userId);
		
		return ResponseEntity.ok(
				BaseResponse.<UserProfilesResolveResponseDto>builder()
				            .success(true)
				            .message("Profiles resolved")
				            .code(200)
				            .data(data)
				            .build()
		);
	}
}