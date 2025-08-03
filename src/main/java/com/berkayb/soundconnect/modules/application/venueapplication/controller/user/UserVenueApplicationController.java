package com.berkayb.soundconnect.modules.application.venueapplication.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueApplication.*;

@Slf4j
@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "User / Venue Application", description = "Venue applications (user operations)")
public class UserVenueApplicationController {

	private final VenueApplicationService venueApplicationService;
	
	
	// kulllanici basvurusu
	@PostMapping(CREATE)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> createVenueApplication(@AuthenticationPrincipal UserDetailsImpl userDetails,
																							@RequestBody @Valid VenueApplicationCreateRequestDto dto) {
		log.info("User {} creates venue application", userDetails.getUsername());
		var response = venueApplicationService.createApplication(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<VenueApplicationResponseDto>builder()
				                         .success(true)
				                         .message("basvuru olusturuldu")
				                         .code(201)
				                         .data(response)
				                         .build());
	}
	
	// kullanici kendi basvurularini listeler
	@GetMapping(MY_APPLICATIONS)
	public ResponseEntity<BaseResponse<List<VenueApplicationResponseDto>>> getVenueApplications(@AuthenticationPrincipal UserDetailsImpl userDetails) {
		log.info("User {} requests all venue applications", userDetails.getUsername());
		var list = venueApplicationService.getApplicationsByUser(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<List<VenueApplicationResponseDto>>builder()
				                         .success(true)
				                         .message("Basvurularin listelendi")
				                         .code(200)
				                         .data(list)
				                         .build());
	}
	
	// kullanici bekleyen basvurusu varsa gorur
	 @GetMapping(MY_PENDING)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> getMypendingVenueApplication(@AuthenticationPrincipal UserDetailsImpl userDetails) {
		log.info("User {} requests pending venue application", userDetails.getUsername());
		var response = venueApplicationService.getPendingApplicationByUser(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<VenueApplicationResponseDto>builder()
				                         .success(true)
				                         .message("bekleyen basvurun getirildi.")
				                         .code(200)
				                         .data(response)
				                         .build());
	 }
}