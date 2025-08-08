package com.berkayb.soundconnect.modules.profile.OrganizerProfile.controller.user;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request.OrganizerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.service.OrganizerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.OrganizerProfile.*;
import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioProfile.ME;
import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioProfile.UPDATE;


@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Organizer Profile", description = "User kendi Organizer profilini yönetir")
public class OrganizerProfileUserController {
	private final OrganizerProfileService organizerProfileService;
	
	@GetMapping(ME)
	public ResponseEntity<BaseResponse<OrganizerProfileResponseDto>> getMyProfile(@AuthenticationPrincipal UserDetailsImpl userDetails) {  // @AuthenticationPrincipal: Authenticated olmus kullanicinin
		// bilgilerini direkt olarak buraya enjekte ediyoruz.
		var dto = organizerProfileService.getProfileByUserId(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<OrganizerProfileResponseDto>builder().success(true).code(200)
		                                     .message("Profil getirildi").data(dto).build());
	}
	
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<OrganizerProfileResponseDto>> updateProfile(@AuthenticationPrincipal UserDetailsImpl userDetails, @RequestBody OrganizerProfileSaveRequestDto dto) {
		var updated = organizerProfileService.updateProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<OrganizerProfileResponseDto>builder().success(true).code(200)
		                                     .message("Profil guncellendi").data(updated).build());
	}
}