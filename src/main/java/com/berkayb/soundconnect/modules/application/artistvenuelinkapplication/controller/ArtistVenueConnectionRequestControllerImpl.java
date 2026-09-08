package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestPageItemDto;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service.ArtistVenueConnectionRequestService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ArtistVenueConnections.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "FOR USERS / Artist Venue Connections", description = "Artist - Venue Connections Management")
public class ArtistVenueConnectionRequestControllerImpl implements ArtistVenueConnectionRequestController {

	private final ArtistVenueConnectionRequestService service;

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@GetMapping(GET_REQUESTS_BY_BAND + "/page")
	@Override
	public ResponseEntity<BaseResponse<PageResponse<ArtistVenueConnectionRequestPageItemDto>>> getBandPage(
			@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable UUID bandId,
			@RequestParam(required = false) RequestStatus status, @RequestParam(required = false) Boolean incoming,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return pageResponse(service.getBandPage(userId(userDetails), bandId, status, incoming, page, size));
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@GetMapping(GET_REQUESTS_BY_MUSICIAN + "/page")
	@Override
	public ResponseEntity<BaseResponse<PageResponse<ArtistVenueConnectionRequestPageItemDto>>> getMusicianPage(
			@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable UUID musicianProfileId,
			@RequestParam(required = false) RequestStatus status, @RequestParam(required = false) Boolean incoming,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return pageResponse(service.getMusicianPage(userId(userDetails), musicianProfileId, status, incoming, page, size));
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@GetMapping(GET_REQUESTS_BY_VENUE + "/page")
	@Override
	public ResponseEntity<BaseResponse<PageResponse<ArtistVenueConnectionRequestPageItemDto>>> getVenuePage(
			@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable UUID venueId,
			@RequestParam(required = false) RequestStatus status, @RequestParam(required = false) Boolean incoming,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return pageResponse(service.getVenuePage(userId(userDetails), venueId, status, incoming, page, size));
	}

	private ResponseEntity<BaseResponse<PageResponse<ArtistVenueConnectionRequestPageItemDto>>> pageResponse(
			PageResponse<ArtistVenueConnectionRequestPageItemDto> page) {
		return ResponseEntity.ok(BaseResponse.<PageResponse<ArtistVenueConnectionRequestPageItemDto>>builder()
				.success(true).message("Bağlantı istekleri getirildi.").data(page).build());
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@GetMapping(GET_REQUESTS_BY_BAND)
	@Override
	public ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByBand(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@RequestParam(required = false) RequestStatus status) {

		var responseList = service.getRequestsByBand(userId(userDetails), bandId, status);
		return ResponseEntity.ok(
				BaseResponse.<List<ArtistVenueConnectionRequestResponseDto>>builder()
				            .success(true)
				            .message("Band basvurulari getirildi.")
				            .data(responseList)
				            .build()
		);
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@PostMapping(CANCEL)
	@Override
	public ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> cancelRequest(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID requestId) {
		var response = service.cancelRequest(userId(userDetails), requestId);
		return ResponseEntity.ok(
				BaseResponse.<ArtistVenueConnectionRequestResponseDto>builder()
				            .success(true)
				            .message("Basvuru iptal edildi.")
				            .data(response)
				            .build()
		);
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@DeleteMapping(DISCONNECT)
	@Override
	public ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> disconnect(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID requestId) {
		var response = service.disconnect(userId(userDetails), requestId);
		return ResponseEntity.ok(
				BaseResponse.<ArtistVenueConnectionRequestResponseDto>builder()
				            .success(true)
				            .message("Mekan baglantisi kaldirildi.")
				            .data(response)
				            .build()
		);
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@PostMapping(REQUEST)
	@Override
	public ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> createRequest(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody @Valid ArtistVenueConnectionRequestCreateDto dto,
			@RequestParam("requestByType") RequestByType requestByType) {
		log.info("ArtistVenueConnectionRequestController: yeni request baslatiliyor.");
		var response = service.createRequest(userId(userDetails), dto, requestByType);
		return ResponseEntity.ok(
				BaseResponse.<ArtistVenueConnectionRequestResponseDto>builder()
				            .success(true)
				            .message("Basvuru basariyla olusturuldu.")
				            .data(response)
				            .build()
		);
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@PostMapping(ACCEPT)
	@Override
	public ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> acceptRequest(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID requestId) {
		var response = service.acceptRequest(userId(userDetails), requestId);
		return ResponseEntity.ok(
				BaseResponse.<ArtistVenueConnectionRequestResponseDto>builder()
				            .success(true)
				            .message("Basvuru basariyla onaylandi.")
				            .data(response)
				            .build()
		);
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@PostMapping(REJECT)
	@Override
	public ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> rejectRequest(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID requestId) {
		var response = service.rejectRequest(userId(userDetails), requestId);
		return ResponseEntity.ok(
				BaseResponse.<ArtistVenueConnectionRequestResponseDto>builder()
				            .success(true)
				            .message("Basvuru reddedildi.")
				            .data(response)
				            .build()
		);
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@GetMapping(GET_REQUESTS_BY_MUSICIAN)
	@Override
	public ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByMusicianProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID musicianProfileId,
			@RequestParam(required = false) RequestStatus status) {
		var responseList = service.getRequestByMusicianProfile(userId(userDetails), musicianProfileId, status);
		return ResponseEntity.ok(
				BaseResponse.<List<ArtistVenueConnectionRequestResponseDto>>builder()
				            .success(true)
				            .message("Tum basvurular getirildi.")
				            .data(responseList)
				            .build()
		);
	}

	@PreAuthorize("hasAnyRole('MUSICIAN', 'VENUE')")
	@GetMapping(GET_REQUESTS_BY_VENUE)
	@Override
	public ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByVenue(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID venueId,
			@RequestParam(required = false) RequestStatus status) {
		var responseList = service.getRequestsByVenue(userId(userDetails), venueId, status);
		return ResponseEntity.ok(
				BaseResponse.<List<ArtistVenueConnectionRequestResponseDto>>builder()
				            .success(true)
				            .message("Tum basvurular getirildi.")
				            .data(responseList)
				            .build()
		);
	}

	private UUID userId(UserDetailsImpl userDetails) {
		return userDetails.getUser().getId();
	}
}
