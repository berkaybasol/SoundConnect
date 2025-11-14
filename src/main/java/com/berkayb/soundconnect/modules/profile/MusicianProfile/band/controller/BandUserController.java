package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Band.*;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Band", description = "Kullanıcıya ait Band işlemleri")
public class BandUserController {

	private final BandService bandService;
	
	@PostMapping(CREATE)
	@Operation(summary = "Yeni band (grup) oluşturur")
	public ResponseEntity<BaseResponse<BandResponseDto>> createBand(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody BandCreateRequestDto dto
	) {
		BandResponseDto created = bandService.createBand(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<BandResponseDto>builder()
		                                     .success(true)
		                                     .code(201)
		                                     .message("Band oluşturuldu")
		                                     .data(created)
		                                     .build());
	}
	
	
	@Operation(summary = "kullaniciya ait bandlerin listesini getirir")
	@GetMapping(MY_BANDS)
	public ResponseEntity<BaseResponse<List<BandResponseDto>>> getMyBands(@AuthenticationPrincipal UserDetailsImpl userDetails) {
		var list = bandService.getBandsByUser(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<List<BandResponseDto>>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Bandler listelendi")
		                                     .data(list)
		                                     .build());
	}
	
	@GetMapping(BY_ID)
	@Operation(summary = "Band detayini getirir")
	public ResponseEntity<BaseResponse<BandResponseDto>> getBandById(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId
	) {
		var dto = bandService.getBandById(bandId, userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<BandResponseDto>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Band detayi getirildi.")
		                                     .data(dto)
		                                     .build());
	}
	
	
	@Operation(summary = "Davet yolla")
	@PostMapping(INVITE)
	public ResponseEntity<BaseResponse<Void>> inviteMember (
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@RequestParam UUID invitedUserId,
			@RequestParam (required = false) String message
	) {
		bandService.inviteMember(bandId, userDetails.getUser().getId(), invitedUserId, message);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Davet gonderildi")
				                         .build());
	}
	
	@Operation(summary = "Daveti kabul et")
	@PostMapping(ACCEPT_INVITE)
	public ResponseEntity<BaseResponse<Void>> acceptInvite (
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId
	) {
		bandService.acceptInvite(bandId, userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<Void>builder()
				                         .success(true)
				                         .code(200)
				                         .message("davet kabul edildi")
				                         .build());
	}
	
	@Operation(summary = "Daveti reddet")
	@PostMapping(REJECT_INVITE)
	public ResponseEntity<BaseResponse<Void>> rejectInvite (
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId
	) {
		bandService.rejectInvite(bandId, userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<Void>builder()
				                         .success(true)
				                         .code(200)
				                         .message("davet reddedildi")
				                         .build());
	}
	
	@Operation(summary = "uye cikar")
	@DeleteMapping(REMOVE_MEMBER)
	public ResponseEntity<BaseResponse<Void>> removeMember (
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@PathVariable UUID userId
	) {
		bandService.removeMember(bandId, userDetails.getUser().getId(), userId);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
				                         .success(true)
				                         .code(200)
				                         .message("uye cikarildi")
				                         .build());
	}
	
	@Operation(summary = "bandden ayril")
	@PatchMapping(LEAVE)
	public ResponseEntity<BaseResponse<Void>> leaveBand (
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId
	) {
		bandService.leaveBand(bandId, userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<Void>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Bandden ayrildin")
				                         .build());
				
	}
	
	
	
}