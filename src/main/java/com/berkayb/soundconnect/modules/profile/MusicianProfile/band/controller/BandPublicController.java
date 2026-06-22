package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.controller; //eklendi

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto; //eklendi
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandSearchItemDto; //eklendi
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService; //eklendi
import com.berkayb.soundconnect.shared.response.BaseResponse; //eklendi
import io.swagger.v3.oas.annotations.tags.Tag; //eklendi
import lombok.RequiredArgsConstructor; //eklendi
import org.springframework.http.ResponseEntity; //eklendi
import org.springframework.web.bind.annotation.GetMapping; //eklendi
import org.springframework.web.bind.annotation.PathVariable; //eklendi
import org.springframework.web.bind.annotation.RequestMapping; //eklendi
import org.springframework.web.bind.annotation.RequestParam; //eklendi
import org.springframework.web.bind.annotation.RestController; //eklendi

import java.util.List; //eklendi
import java.util.UUID; //eklendi

import static com.berkayb.soundconnect.shared.constant.EndPoints.Band.*;

@RestController //eklendi
@RequestMapping(PUBLIC_BASE) //eklendi
@RequiredArgsConstructor //eklendi
@Tag(name = "PUBLIC / Band", description = "Public band endpoints") //eklendi
public class BandPublicController { //eklendi
	
	private final BandService bandService; //eklendi
	
	@GetMapping(SEARCH) //eklendi
	public ResponseEntity<BaseResponse<List<BandSearchItemDto>>> searchBands( //eklendi
	                                                                          @RequestParam String q //eklendi
	) { //eklendi
		var result = bandService.searchBands(q); //eklendi
		
		return ResponseEntity.ok( //eklendi
		                          BaseResponse.<List<BandSearchItemDto>>builder() //eklendi
		                                      .success(true) //eklendi
		                                      .code(200) //eklendi
		                                      .message("Band search results fetched") //eklendi
		                                      .data(result) //eklendi
		                                      .build() //eklendi
		); //eklendi
	} //eklendi
	
	@GetMapping(PUBLIC_BY_ID) //eklendi
	public ResponseEntity<BaseResponse<BandResponseDto>> getPublicBandById( //eklendi
	                                                                        @PathVariable UUID bandId //eklendi
	) { //eklendi
		var dto = bandService.getPublicBandById(bandId); //eklendi
		
		return ResponseEntity.ok( //eklendi
		                          BaseResponse.<BandResponseDto>builder() //eklendi
		                                      .success(true) //eklendi
		                                      .code(200) //eklendi
		                                      .message("Band public profile fetched") //eklendi
		                                      .data(dto) //eklendi
		                                      .build() //eklendi
		); //eklendi
	} //eklendi
} //eklendi