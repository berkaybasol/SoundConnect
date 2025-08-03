package com.berkayb.soundconnect.modules.profile.VenueProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueProfile.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Tag(name = "Admin / Venue Profile", description = "Admin'in istediği kullanıcının venue profillerini görüp yönetmesi için endpointler")
public class VenueProfileAdminController {
	
	private final VenueProfileService venueProfileService;
	
	// Admin, istediği user'ın sahip olduğu venue profillerini görür (list)
	@GetMapping(BY_USER_ID)
	public ResponseEntity<BaseResponse<List<VenueProfileResponseDto>>> getVenueProfilesByUserId(@PathVariable UUID userId) {
		List<VenueProfileResponseDto> response = venueProfileService.getProfilesByUserId(userId);
		return ResponseEntity.ok(BaseResponse.<List<VenueProfileResponseDto>>builder()
		                                     .success(true).code(200).message("Kullanıcının venue profilleri getirildi").data(response).build());
	}
	
	// Admin, istediği user'ın bir venue'sunun profilini günceller
	@PutMapping(ADMIN_UPDATE)
	public ResponseEntity<BaseResponse<VenueProfileResponseDto>> updateVenueProfileByUserId(
			@PathVariable UUID userId,
			@PathVariable UUID venueId,
			@RequestBody VenueProfileSaveRequestDto dto) {
		VenueProfileResponseDto response = venueProfileService.updateProfileByVenueId(userId, venueId, dto);
		return ResponseEntity.ok(BaseResponse.<VenueProfileResponseDto>builder()
		                                     .success(true).code(200).message("Venue profili güncellendi").data(response).build());
	}
	
	@PostMapping(ADMIN_CREATE)
	public ResponseEntity<BaseResponse<VenueProfileResponseDto>> createVenueProfile(
			@PathVariable UUID venueId,
			@RequestBody VenueProfileSaveRequestDto dto) {
		VenueProfileResponseDto profile = venueProfileService.createProfile(venueId, dto);
		return ResponseEntity.ok(BaseResponse.<VenueProfileResponseDto>builder()
		                                     .success(true).code(201).message("Venue profili admin tarafından oluşturuldu").data(profile).build());
	}
}