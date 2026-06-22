package com.berkayb.soundconnect.modules.profile.VenueProfile.controller.user;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenuePublicProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueProfile.*; //eklendi

@RestController
@RequestMapping(PUBLIC_BASE) //degisti
@RequiredArgsConstructor
@Tag(name = "PUBLIC / Venue Profile", description = "Public venue profile görüntüleme endpointleri")
public class VenueProfilePublicController { //eklendi
	
	private final VenueProfileService venueProfileService; //eklendi
	
	@GetMapping(PUBLIC_DETAIL) //degisti
	public ResponseEntity<BaseResponse<VenuePublicProfileResponseDto>> getVenuePublicProfile(
			@PathVariable UUID venueId) {
		
		VenuePublicProfileResponseDto response = venueProfileService.getPublicProfileDetail(venueId);
		
		return ResponseEntity.ok(BaseResponse.<VenuePublicProfileResponseDto>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Venue public profil detayi getirildi")
		                                     .data(response)
		                                     .build());
	}
}