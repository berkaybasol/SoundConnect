package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandMemberTitleUpdateDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandMemberResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandPendingInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandReceivedInvitationResponseDto;
import com.berkayb.soundconnect.shared.response.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

	@PreAuthorize("hasRole('MUSICIAN') and principal.enabled")
	@GetMapping("/{bandId}/invitations/received/current")
	@Operation(summary = "Oturumdaki müzisyenin bu gruptaki güncel bekleyen davetini getirir")
	public ResponseEntity<BaseResponse<BandReceivedInvitationResponseDto>> getCurrentReceivedInvitation(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId) {
		var invitation = bandService.getCurrentReceivedInvitation(bandId, userDetails.getUser().getId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
				.body(BaseResponse.<BandReceivedInvitationResponseDto>builder()
						.success(true).code(200).message("Güncel davet getirildi.").data(invitation).build());
	}

	@PreAuthorize("hasRole('MUSICIAN') and principal.enabled")
	@GetMapping("/invitations/received")
	@Operation(summary = "Oturumdaki müzisyenin gelen grup davetlerini sayfalı listeler")
	public ResponseEntity<BaseResponse<PageResponse<BandReceivedInvitationResponseDto>>> getReceivedInvitations(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		var invitations = bandService.getReceivedInvitations(userDetails.getUser().getId(), page, size);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
				.body(BaseResponse.<PageResponse<BandReceivedInvitationResponseDto>>builder()
						.success(true).code(200).message("Gelen davetler listelendi.").data(invitations).build());
	}

	@PreAuthorize("hasRole('MUSICIAN') and principal.enabled")
	@GetMapping("/{bandId}/invitations/pending")
	@Operation(summary = "Kurucunun onay bekleyen grup davetlerini sayfalı listeler")
	public ResponseEntity<BaseResponse<PageResponse<BandPendingInvitationResponseDto>>> getPendingInvitations(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		var invitations = bandService.getPendingInvitations(bandId, userDetails.getUser().getId(), page, size);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
				.body(BaseResponse.<PageResponse<BandPendingInvitationResponseDto>>builder()
						.success(true).code(200).message("Bekleyen davetler listelendi.").data(invitations).build());
	}

	@PreAuthorize("hasRole('MUSICIAN') and principal.enabled")
	@PatchMapping("/{bandId}/members/{userId}/title")
	@Operation(summary = "Aktif grup üyesinin görünen başlığını düzenler")
	public ResponseEntity<BaseResponse<BandMemberResponseDto>> updateMemberTitle(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@PathVariable UUID userId,
			@Valid @RequestBody BandMemberTitleUpdateDto update) {
		var member = bandService.updateMemberTitle(bandId, userDetails.getUser().getId(), userId, update);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
				.body(BaseResponse.<BandMemberResponseDto>builder().success(true).code(200)
						.message("Üye başlığı güncellendi.").data(member).build());
	}
	
	@PreAuthorize("hasRole('MUSICIAN')")
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
	
	@PreAuthorize("hasRole('MUSICIAN')")
	@PutMapping(BY_ID) //eklendi
	@Operation(summary = "Band profilini gunceller") //eklendi
	public ResponseEntity<BaseResponse<BandResponseDto>> updateBand( //eklendi
	                                                                 @AuthenticationPrincipal UserDetailsImpl userDetails, //eklendi
	                                                                 @PathVariable UUID bandId, //eklendi
	                                                                 @RequestBody BandCreateRequestDto dto //eklendi
	) { //eklendi
		BandResponseDto updated = bandService.updateBand(bandId, userDetails.getUser().getId(), dto); //eklendi
		return ResponseEntity.ok(BaseResponse.<BandResponseDto>builder() //eklendi
		                                     .success(true) //eklendi
		                                     .code(200) //eklendi
		                                     .message("Band guncellendi") //eklendi
		                                     .data(updated) //eklendi
		                                     .build()); //eklendi
	} //eklendi
	
	
	@Operation(summary = "kullaniciya ait bandlerin listesini getirir")
	@PreAuthorize("hasRole('MUSICIAN')")
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
	
	@PreAuthorize("hasRole('MUSICIAN')")
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

	@PreAuthorize("hasRole('MUSICIAN')")
	@DeleteMapping(DELETE)
	@Operation(summary = "Band siler")
	public ResponseEntity<BaseResponse<Void>> deleteBand(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId
	) {
		bandService.deleteBand(bandId, userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Band silindi")
		                                     .build());
	}
	
	
	@Operation(summary = "Davet yolla")
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(INVITE)
	public ResponseEntity<BaseResponse<Void>> inviteMember(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@RequestParam UUID invitedUserId,
			@RequestParam(required = false) String message
	) {
		bandService.inviteMember(bandId, userDetails.getUser().getId(), invitedUserId, message);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Davet gonderildi")
		                                     .build());
	}
	
	@Operation(summary = "Daveti kabul et")
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(ACCEPT_INVITE)
	public ResponseEntity<BaseResponse<Void>> acceptInvite(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@RequestParam(required = false) UUID invitationId
	) {
		bandService.acceptInvite(bandId, userDetails.getUser().getId(), invitationId);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("davet kabul edildi")
		                                     .build());
	}
	
	@Operation(summary = "Daveti reddet")
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(REJECT_INVITE)
	public ResponseEntity<BaseResponse<Void>> rejectInvite(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@RequestParam(required = false) UUID invitationId
	) {
		bandService.rejectInvite(bandId, userDetails.getUser().getId(), invitationId);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("davet reddedildi")
		                                     .build());
	}
	
	@Operation(summary = "uye cikar")
	@PreAuthorize("hasRole('MUSICIAN')")
	@DeleteMapping(REMOVE_MEMBER)
	public ResponseEntity<BaseResponse<Void>> removeMember(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@PathVariable UUID userId,
			@RequestParam(required = false) Long expectedTitleVersion
	) {
		bandService.removeMember(bandId, userDetails.getUser().getId(), userId, expectedTitleVersion);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("uye cikarildi")
		                                     .build());
	}
	
	@Operation(summary = "bandden ayril")
	@PreAuthorize("hasRole('MUSICIAN')")
	@PatchMapping(LEAVE)
	public ResponseEntity<BaseResponse<Void>> leaveBand(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@RequestParam(required = false) Long expectedTitleVersion
	) {
		bandService.leaveBand(bandId, userDetails.getUser().getId(), expectedTitleVersion);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Bandden ayrildin")
		                                     .build());
		
	}

	@Deprecated(forRemoval = false)
	@Operation(summary = "bandden ayril (legacy PUT)", deprecated = true)
	@PreAuthorize("hasRole('MUSICIAN')")
	@PutMapping(LEAVE)
	public ResponseEntity<BaseResponse<Void>> leaveBandLegacy(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId,
			@RequestParam(required = false) Long expectedTitleVersion
	) {
		return leaveBand(userDetails, bandId, expectedTitleVersion);
	}
}
