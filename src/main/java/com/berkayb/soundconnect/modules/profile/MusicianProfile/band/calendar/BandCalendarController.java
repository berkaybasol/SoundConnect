package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse.MusicianCalendarRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController @RequiredArgsConstructor
public class BandCalendarController {
	private final BandCalendarService service;
	private final MusicianCalendarRateLimitGuard guard;

	@PreAuthorize("hasRole('MUSICIAN')")
	@GetMapping("/api/v1/user/bands/{bandId}/calendar-settings")
	public ResponseEntity<BaseResponse<MusicianCalendarSettingsResponse>> getSettings(
			@PathVariable UUID bandId, @AuthenticationPrincipal UserDetailsImpl principal) {
		throw new com.berkayb.soundconnect.shared.exception.SoundConnectException(
				com.berkayb.soundconnect.shared.exception.ErrorType.EVENT_CALENDAR_SETTINGS_RETIRED);
	}

	@PreAuthorize("hasRole('MUSICIAN')")
	@PutMapping("/api/v1/user/bands/{bandId}/calendar-settings")
	public ResponseEntity<BaseResponse<MusicianCalendarSettingsResponse>> updateSettings(
			@PathVariable UUID bandId, @AuthenticationPrincipal UserDetailsImpl principal,
			@Valid @RequestBody MusicianCalendarSettingsUpdate update) {
		throw new com.berkayb.soundconnect.shared.exception.SoundConnectException(
				com.berkayb.soundconnect.shared.exception.ErrorType.EVENT_CALENDAR_SETTINGS_RETIRED);
	}

	@GetMapping("/api/v1/public/bands/{bandId}/calendar")
	public ResponseEntity<BaseResponse<MusicianCalendarResponse>> getCalendar(@PathVariable UUID bandId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return response(service.getCalendar(bandId, startDate, endDate, page, size));
	}

	private static <T> ResponseEntity<BaseResponse<T>> response(T data) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
				.body(BaseResponse.<T>builder().success(true).code(200).message("Takvim bilgileri getirildi").data(data).build());
	}
}
