package com.berkayb.soundconnect.modules.profile.controller;

import com.berkayb.soundconnect.modules.profile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.service.VenueProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import static com.berkayb.soundconnect.shared.constant.EndPoints.ProfileVenue.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Venue Profile", description = "Venue profile management endpoints")
public class VenueProfileControllerImpl implements VenueProfileController {
	private final VenueProfileService venueProfileService;
	
	// PROFİL OLUŞTURMA (Admin veya ilk setup için)
	@PostMapping("/create-profile/{venueId}")
	@Override
	public ResponseEntity<BaseResponse<VenueProfileResponseDto>> createProfile(
			@PathVariable UUID venueId,
			@RequestBody VenueProfileSaveRequestDto dto) {
		VenueProfileResponseDto profile = venueProfileService.createProfile(venueId, dto);
		return ResponseEntity.ok(BaseResponse.<VenueProfileResponseDto>builder()
		                                     .success(true)
		                                     .message("Venue profili oluşturuldu.")
		                                     .data(profile)
		                                     .build());
	}
	
	// PROFİLİ GETİR (Sahibi veya public bakış)
	@GetMapping("/{venueId}/profile")
	@Override
	public ResponseEntity<BaseResponse<VenueProfileResponseDto>> getProfile(
			@PathVariable UUID venueId) {
		VenueProfileResponseDto profile = venueProfileService.getProfileByVenueId(venueId);
		return ResponseEntity.ok(BaseResponse.<VenueProfileResponseDto>builder()
		                                     .success(true)
		                                     .message("Venue profili getirildi.")
		                                     .data(profile)
		                                     .build());
	}
	
	// PROFİL GÜNCELLEME (sadece sahibi veya admin)
	@PutMapping("/{venueId}/profile")
	@Override
	public ResponseEntity<BaseResponse<VenueProfileResponseDto>> updateProfile(
			@PathVariable UUID venueId,
			@RequestBody VenueProfileSaveRequestDto dto) {
		VenueProfileResponseDto profile = venueProfileService.updateProfile(venueId, dto);
		return ResponseEntity.ok(BaseResponse.<VenueProfileResponseDto>builder()
		                                     .success(true)
		                                     .message("Venue profili güncellendi.")
		                                     .data(profile)
		                                     .build());
	}
}