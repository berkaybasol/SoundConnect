package com.berkayb.soundconnect.modules.profile.MusicianProfile.controller.publicapi;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileSearchItemDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.MusicianProfile.*;

@RestController
@RequestMapping(PUBLIC_BASE)
@RequiredArgsConstructor
@Tag(name = "PUBLIC / Musician Profile", description = "Public musician profile endpoints")
public class MusicianProfilePublicController {
	
	private final MusicianProfileService musicianProfileService;
	
	@GetMapping(SEARCH)
	public ResponseEntity<BaseResponse<List<MusicianProfileSearchItemDto>>> searchProfiles(
			@RequestParam String q) {
		
		var result = musicianProfileService.searchProfiles(q);
		
		return ResponseEntity.ok(
				BaseResponse.<List<MusicianProfileSearchItemDto>>builder()
				            .success(true)
				            .code(200)
				            .message("Musician search results fetched")
				            .data(result)
				            .build()
		);
	}
	
	@GetMapping(PUBLIC_BY_PROFILE_ID) //eklendi
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> getProfileByProfileId( //eklendi
	                                                                                       @PathVariable UUID profileId) { //eklendi
		
		var dto = musicianProfileService.getProfileByProfileId(profileId); //eklendi
		
		return ResponseEntity.ok( //eklendi
		                          BaseResponse.<MusicianProfileResponseDto>builder() //eklendi
		                                      .success(true) //eklendi
		                                      .code(200) //eklendi
		                                      .message("Musician public profile fetched") //eklendi
		                                      .data(dto) //eklendi
		                                      .build() //eklendi
		); //eklendi
	}
}