package com.berkayb.soundconnect.modules.profile.VenueProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueProfile.*;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "User / Venue Profile", description = "User'ın sahip olduğu venue profillerini yönetmesi için endpointler")
public class VenueProfileUserController {
	
	private final VenueProfileService venueProfileService;
	
	// Kullanıcının sahip olduğu tüm venue profilleri (list)
	@GetMapping(ME)
	public ResponseEntity<BaseResponse<List<VenueProfileResponseDto>>> getMyVenueProfiles(
			@AuthenticationPrincipal UserDetailsImpl userDetails) {
		List<VenueProfileResponseDto> response = venueProfileService.getProfilesByUserId(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<List<VenueProfileResponseDto>>builder()
		                                     .success(true).code(200).message("Kendi Venue profilleri getirildi").data(response).build());
	}
	
	// Kullanıcı kendi venue'sinin profilini günceller
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<VenueProfileResponseDto>> updateMyVenueProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID venueId,
			@RequestBody VenueProfileSaveRequestDto dto) {
		VenueProfileResponseDto response = venueProfileService.updateProfileByVenueId(userDetails.getUser().getId(), venueId, dto);
		return ResponseEntity.ok(BaseResponse.<VenueProfileResponseDto>builder()
		                                     .success(true).code(200).message("Venue profili güncellendi").data(response).build());
	}
}