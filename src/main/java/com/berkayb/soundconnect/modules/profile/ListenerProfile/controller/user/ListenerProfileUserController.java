package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse.ListenerPlaylistRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse.ListenerVisibilityRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerAvatarUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerVisibilityUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerPlaylistsUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerPlaylistService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ListenerProfile.*;

/**
 * * @AuthenticationPrincipal açıklaması:
 *
 *  * - Spring Security, authenticated (giriş yapmış) kullanıcının kimliğini (Principal)
 *  *   otomatik olarak controller metoduna enjekte etmemizi sağlar.
 *  * - Bu annotation ile JWT’den veya session’dan doğrulanan kullanıcıyı doğrudan parametre olarak alırız.
 *  * - Yani request’te userId gibi hassas bir bilgiyi taşımak zorunda kalmayız.
 *  * - Arka planda SecurityContext’in içindeki Principal nesnesi burada UserDetailsImpl olarak gelir.
 *  *
 *  * Avantajları:
 *  *   - Kullanıcı manipülasyonunu engeller (başkasının userId’sini göndermeye kalkamaz).
 *  *   - Her endpointte id çekmekle uğraşmaz, güvenli ve sade bir kod üretiriz.
 *  *   - IDE ile otomatik tamamlama ve Swagger/OpenAPI dokümantasyonuna daha net yansır.
 */

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@PreAuthorize("hasRole('LISTENER')")
@Tag(name = "FOR USERS / Listener Profile", description = "Operations related to the profile of users with a Listener " +
		"profile")
public class ListenerProfileUserController {
	private final ListenerProfileService listenerProfileService;
	private final ListenerPlaylistService listenerPlaylistService;
	private final ListenerPlaylistRateLimitGuard playlistRateLimitGuard;
	private final ListenerVisibilityRateLimitGuard visibilityRateLimitGuard;
	
	// getir
	@GetMapping(ME)
	public ResponseEntity<BaseResponse<ListenerProfileOwnerResponseDto>> getMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails) {
		ListenerProfileOwnerResponseDto response = listenerProfileService.getMyProfile(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<ListenerProfileOwnerResponseDto>builder()
		                                     .success(true).code(200).message("Profil getirildi").data(response).build());
	}
	
	// olustur
	@PostMapping(CREATE)
	public ResponseEntity<BaseResponse<ListenerProfileOwnerResponseDto>> createMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody ListenerSaveRequestDto dto) {
		ListenerProfileOwnerResponseDto response = listenerProfileService.createProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.status(HttpStatus.CREATED)
		                     .body(BaseResponse.<ListenerProfileOwnerResponseDto>builder()
		                                       .success(true).code(201).message("Profil oluşturuldu")
		                                       .data(response).build());
	}
	
	// guncelle
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<ListenerProfileOwnerResponseDto>> updateMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody ListenerSaveRequestDto dto) {
		ListenerProfileOwnerResponseDto response = listenerProfileService.updateMyProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileOwnerResponseDto>builder()
		                                     .success(true).code(200).message("Profil güncellendi").data(response).build());
	}

	@PatchMapping(AVATAR)
	public ResponseEntity<BaseResponse<ListenerProfileOwnerResponseDto>> updateMyAvatar(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody ListenerAvatarUpdateRequestDto dto) {
		ListenerProfileOwnerResponseDto response = listenerProfileService.updateAvatar(
				userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileOwnerResponseDto>builder()
		                                     .success(true).code(200).message("Profil fotoğrafı güncellendi")
		                                     .data(response).build());
	}

	@PatchMapping(VISIBILITY)
	public ResponseEntity<BaseResponse<ListenerProfileOwnerResponseDto>> updateMyVisibility(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody ListenerVisibilityUpdateRequestDto dto) {
		UUID userId = userDetails.getUser().getId();
		visibilityRateLimitGuard.check(userId);
		ListenerProfileOwnerResponseDto response = listenerProfileService.updateVisibility(userId, dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileOwnerResponseDto>builder()
		                                     .success(true).code(200).message("Profil görünürlüğü güncellendi")
		                                     .data(response).build());
	}

	@PutMapping(PLAYLISTS)
	public ResponseEntity<BaseResponse<ListenerProfileOwnerResponseDto>> replaceMyPlaylists(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody ListenerPlaylistsUpdateRequestDto dto
	) {
		UUID userId = userDetails.getUser().getId();
		playlistRateLimitGuard.check(userId);
		ListenerProfileOwnerResponseDto response = listenerPlaylistService.replacePlaylists(
				userId,
				dto
		);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileOwnerResponseDto>builder()
				.success(true)
				.code(200)
				.message("Çalma listeleri güncellendi")
				.data(response)
				.build());
	}
	
}
