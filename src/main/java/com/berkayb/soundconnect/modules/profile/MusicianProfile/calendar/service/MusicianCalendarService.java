package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.service;

import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.entity.MusicianCalendarSettings;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository.MusicianCalendarEventRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository.MusicianCalendarSettingsRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MusicianCalendarService {
	// Far above normal membership limits, but bounds public-request lock work.
	// Overflow is rejected instead of silently omitting part of a valid feed.
	static final int MAX_LOCKED_BANDS = 128;

	private final MusicianCalendarSettingsRepository settingsRepository;
	private final MusicianCalendarEventRepository eventRepository;
	private final EventMapper eventMapper;

	// Transactions intentionally aren't readOnly: PostgreSQL forbids FOR SHARE in
	// a read-only transaction. Shared parent locks serialize privacy reads with
	// preference writes, including profiles with no settings row yet.
	@Transactional
	public MusicianCalendarSettingsResponse getSettings(UUID userId) {
		UUID profileId = settingsRepository.lockOwnedProfileForRead(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		return response(settings(profileId));
	}

	@Transactional
	public MusicianCalendarSettingsResponse updateSettings(UUID userId, MusicianCalendarSettingsUpdate update) {
		if (update == null || update.visible() == null || update.version() == null
				|| update.version() < 0 || update.version() == Long.MAX_VALUE) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		UUID profileId = settingsRepository.lockOwnedProfileForUpdate(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		MusicianCalendarSettings current = settings(profileId);
		if (update.version() != current.getVersion()) {
			// The same successful request can safely be retried after a lost reply.
			if (current.isVisible() == update.visible() && current.getVersion() == update.version() + 1) {
				return response(current);
			}
			throw new SoundConnectException(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT);
		}
		if (current.isVisible() == update.visible()) return response(current);
		current.setVisible(update.visible());
		current.setVersion(current.getVersion() + 1);
		settingsRepository.saveAndFlush(current);
		return response(current);
	}

	@Transactional
	public MusicianCalendarResponse getCalendar(UUID profileId, LocalDate startDate, LocalDate endDate, int page, int size) {
		validateQuery(startDate, endDate, page, size);
		// Always band -> musician, never the reverse. No event/member entity is
		// loaded before its privacy fence, and newly joined bands cannot enter the
		// slice/detail queries without having their parent locked in this snapshot.
		List<UUID> lockedBandIds = settingsRepository.lockCalendarBandsForRead(profileId, startDate, endDate, MAX_LOCKED_BANDS + 1);
		if (lockedBandIds.size() > MAX_LOCKED_BANDS) {
			throw new SoundConnectException(ErrorType.MUSICIAN_CALENDAR_QUERY_INVALID);
		}
		settingsRepository.lockProfileForRead(profileId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		var ids = eventRepository.findApprovedEventIds(profileId, startDate, endDate, lockedBandIds, PageRequest.of(page, size));
		if (ids.isEmpty()) return new MusicianCalendarResponse(profileId, startDate, endDate, false, List.of(), page, size, false);
		var details = eventRepository.findCalendarDetails(ids.getContent(), profileId, lockedBandIds).stream()
				.collect(Collectors.toMap(event -> event.getId(), Function.identity()));
		var events = ids.getContent().stream().map(details::get).filter(Objects::nonNull).map(eventMapper::toDto).toList();
		return new MusicianCalendarResponse(profileId, startDate, endDate, !events.isEmpty() || ids.hasNext(), events, page, size, ids.hasNext());
	}

	private MusicianCalendarSettings settings(UUID profileId) {
		return settingsRepository.findById(profileId).orElseGet(() -> new MusicianCalendarSettings(profileId));
	}

	private static MusicianCalendarSettingsResponse response(MusicianCalendarSettings settings) {
		return new MusicianCalendarSettingsResponse(settings.isVisible(), settings.getVersion());
	}

	public static void validateQuery(LocalDate startDate, LocalDate endDate, int page, int size) {
		if (startDate == null || endDate == null || endDate.isBefore(startDate)
				|| startDate.getYear() < 1 || startDate.getYear() > 9999 || endDate.getYear() > 9999
				|| ChronoUnit.DAYS.between(startDate, endDate) > 30 || page < 0 || page > 100 || size < 1 || size > 50) {
			throw new SoundConnectException(ErrorType.MUSICIAN_CALENDAR_QUERY_INVALID);
		}
	}
}
