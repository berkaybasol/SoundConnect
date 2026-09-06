package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse.MusicianCalendarRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.service.MusicianCalendarService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.MusicianProfile.PUBLIC_BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.MusicianProfile.USER_BASE;

@RestController
@RequiredArgsConstructor
public class MusicianCalendarController {

	private final MusicianCalendarService calendarService;
	private final MusicianCalendarRateLimitGuard rateLimitGuard;

	@PreAuthorize("hasRole('MUSICIAN')")
	@GetMapping(USER_BASE + "/me/calendar-settings")
	public ResponseEntity<BaseResponse<MusicianCalendarSettingsResponse>> getSettings(
			@AuthenticationPrincipal UserDetailsImpl principal
	) {
		throw new com.berkayb.soundconnect.shared.exception.SoundConnectException(
				com.berkayb.soundconnect.shared.exception.ErrorType.EVENT_CALENDAR_SETTINGS_RETIRED);
	}

	@PreAuthorize("hasRole('MUSICIAN')")
	@PutMapping(USER_BASE + "/me/calendar-settings")
	public ResponseEntity<BaseResponse<MusicianCalendarSettingsResponse>> updateSettings(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@Valid @RequestBody MusicianCalendarSettingsUpdate update
	) {
		throw new com.berkayb.soundconnect.shared.exception.SoundConnectException(
				com.berkayb.soundconnect.shared.exception.ErrorType.EVENT_CALENDAR_SETTINGS_RETIRED);
	}

	@GetMapping(PUBLIC_BASE + "/{profileId}/calendar")
	public ResponseEntity<BaseResponse<MusicianCalendarResponse>> getCalendar(
			@PathVariable UUID profileId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size
	) {
		return response(calendarService.getCalendar(profileId, startDate, endDate, page, size));
	}

	private static <T> ResponseEntity<BaseResponse<T>> response(T data) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
				.body(BaseResponse.<T>builder().success(true).code(200).message("Takvim bilgileri getirildi").data(data).build());
	}
}
