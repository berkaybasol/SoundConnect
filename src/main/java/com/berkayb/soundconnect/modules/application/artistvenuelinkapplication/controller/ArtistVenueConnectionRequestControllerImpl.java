package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.controller;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service.ArtistVenueConnectionRequestService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ArtistVenueConnections.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Artist Venue Connections", description = "Artist - Venue Connections Management")
public class ArtistVenueConnectionRequestControllerImpl implements ArtistVenueConnectionRequestController {
	private final ArtistVenueConnectionRequestService service;
	
	@PostMapping(REQUEST)
	@Override
	public ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> createRequest(@RequestBody @Valid ArtistVenueConnectionRequestCreateDto dto, @RequestParam("requestByType") RequestByType requestByType) {
		log.info("ArtistVenueConnectionRequestController: yeni request başlatılıyor.");
		var response = service.createRequest(dto, requestByType);
		return ResponseEntity.ok(
				BaseResponse.<ArtistVenueConnectionRequestResponseDto>builder()
				            .success(true)
				            .message("Başvuru başarıyla oluşturuldu.")
				            .data(response)
				            .build()
		);
	}
	
	@PostMapping(ACCEPT)
	@Override
	public ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> acceptRequest(@PathVariable UUID requestId) {
		var response = service.acceptRequest(requestId);
		return ResponseEntity.ok(
				BaseResponse.<ArtistVenueConnectionRequestResponseDto>builder()
				            .success(true)
				            .message("Başvuru başarıyla onaylandı.")
				            .data(response)
				            .build()
		);
	}
	
	@PostMapping(REJECT)
	@Override
	public ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> rejectRequest(@PathVariable UUID requestId) {
		var response = service.rejectRequest(requestId);
		return ResponseEntity.ok(
				BaseResponse.<ArtistVenueConnectionRequestResponseDto>builder()
				            .success(true)
				            .message("Başvuru reddedildi.")
				            .data(response)
				            .build()
		);
	}
	@GetMapping(GET_REQUESTS_BY_MUSICIAN)
	@Override
	public ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByMusicianProfile(@PathVariable UUID musicianProfileId) {
		var responseList = service.getRequestByMusicianProfile(musicianProfileId);
		return ResponseEntity.ok(
				BaseResponse.<List<ArtistVenueConnectionRequestResponseDto>>builder()
				            .success(true)
				            .message("Tüm başvurular getirildi.")
				            .data(responseList)
				            .build()
		);
	}
	
	@GetMapping(GET_REQUESTS_BY_VENUE)
	@Override
	public ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByVenue(@PathVariable UUID venueId) {
		var responseList = service.getRequestsByVenue(venueId);
		return ResponseEntity.ok(
				BaseResponse.<List<ArtistVenueConnectionRequestResponseDto>>builder()
				            .success(true)
				            .message("Tüm başvurular getirildi.")
				            .data(responseList)
				            .build()
		);
	}
}