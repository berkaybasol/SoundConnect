package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar;

import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.service.MusicianCalendarService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class BandCalendarService {
	private final BandCalendarSettingsRepository settingsRepository;
	private final BandCalendarEventRepository eventRepository;
	private final EventMapper eventMapper;

	/** Reject outsiders before spending the band's shared rate-limit budget. */
	@Transactional(readOnly = true)
	public void requireFounder(UUID bandId, UUID userId) {
		if (userId == null || !settingsRepository.isActiveFounder(bandId, userId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	@Transactional
	public MusicianCalendarSettingsResponse getSettings(UUID bandId, UUID userId) {
		settingsRepository.lockBandForRead(bandId).orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
		lockFounder(bandId, userId);
		return response(settings(bandId));
	}

	@Transactional
	public MusicianCalendarSettingsResponse updateSettings(UUID bandId, UUID userId, MusicianCalendarSettingsUpdate update) {
		if (update == null || update.visible() == null || update.version() == null || update.version() < 0
				|| update.version() == Long.MAX_VALUE) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		settingsRepository.lockBandForUpdate(bandId).orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
		lockFounder(bandId, userId);
		BandCalendarSettings current = settings(bandId);
		if (update.version() != current.getVersion()) {
			if (current.isVisible() == update.visible() && current.getVersion() == update.version() + 1) return response(current);
			throw new SoundConnectException(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT);
		}
		if (current.isVisible() == update.visible()) return response(current);
		current.setVisible(update.visible());
		current.setVersion(current.getVersion() + 1);
		settingsRepository.saveAndFlush(current);
		return response(current);
	}

	@Transactional
	public MusicianCalendarResponse getCalendar(UUID bandId, LocalDate startDate, LocalDate endDate, int page, int size) {
		MusicianCalendarService.validateQuery(startDate, endDate, page, size);
		settingsRepository.lockBandForRead(bandId).orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
		var ids = eventRepository.findApprovedEventIds(bandId, startDate, endDate, PageRequest.of(page, size));
		if (ids.isEmpty()) return new MusicianCalendarResponse(bandId, startDate, endDate, false, List.of(), page, size, false);
		var details = eventRepository.findCalendarDetails(ids.getContent(), bandId).stream()
				.collect(Collectors.toMap(event -> event.getId(), Function.identity()));
		var events = ids.getContent().stream().map(details::get).filter(Objects::nonNull).map(eventMapper::toDto).toList();
		return new MusicianCalendarResponse(bandId, startDate, endDate, !events.isEmpty() || ids.hasNext(), events, page, size, ids.hasNext());
	}

	private void lockFounder(UUID bandId, UUID userId) {
		if (userId == null || settingsRepository.lockActiveFounder(bandId, userId).isEmpty()) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private BandCalendarSettings settings(UUID bandId) {
		return settingsRepository.findById(bandId).orElseGet(() -> new BandCalendarSettings(bandId));
	}
	private static MusicianCalendarSettingsResponse response(BandCalendarSettings settings) {
		return new MusicianCalendarSettingsResponse(settings.isVisible(), settings.getVersion());
	}
}
