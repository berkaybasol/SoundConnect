package com.berkayb.soundconnect.modules.feed.musician.preference.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesUpdate;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.MusicianFeed.PREFERENCES;
import static com.berkayb.soundconnect.shared.constant.EndPoints.MusicianFeed.BASE;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Musician Feed", description = "Private musician feed preferences and completion")
public class MusicianFeedPreferencesController {
	private final MusicianFeedPreferencesService service;

	@Operation(summary = "Get my opportunity preference and authoritative completion state")
	@PreAuthorize("hasRole('MUSICIAN')")
	@GetMapping(PREFERENCES)
	public ResponseEntity<BaseResponse<MusicianFeedPreferencesResponse>> get(
			@AuthenticationPrincipal UserDetailsImpl principal
	) {
		return response(service.get(userId(principal)), "Akış tercihleri getirildi");
	}

	@Operation(summary = "Replace my opportunity-city preference")
	@PreAuthorize("hasRole('MUSICIAN')")
	@PutMapping(PREFERENCES)
	public ResponseEntity<BaseResponse<MusicianFeedPreferencesResponse>> update(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@Valid @RequestBody MusicianFeedPreferencesUpdate update
	) {
		return response(service.update(userId(principal), update), "Akış tercihleri güncellendi");
	}

	private static UUID userId(UserDetailsImpl principal) {
		if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		return principal.getId();
	}

	private static ResponseEntity<BaseResponse<MusicianFeedPreferencesResponse>> response(
			MusicianFeedPreferencesResponse data,
			String message
	) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore().cachePrivate())
				.body(BaseResponse.<MusicianFeedPreferencesResponse>builder()
						.success(true).code(200).message(message).data(data).build());
	}
}
