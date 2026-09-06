package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.service;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.entity.MusicianCalendarSettings;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository.MusicianCalendarEventRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository.MusicianCalendarSettingsRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MusicianCalendarServiceTest {
	@Mock MusicianCalendarSettingsRepository settingsRepository;
	@Mock MusicianCalendarEventRepository eventRepository;
	@Mock EventMapper eventMapper;
	@InjectMocks MusicianCalendarService service;
	private final UUID userId = UUID.randomUUID();
	private final UUID profileId = UUID.randomUUID();
	private final LocalDate start = LocalDate.of(2026, 9, 5);

	@Test
	void implicitDefaultDoesNotWriteOnSettingsRead() {
		when(settingsRepository.lockOwnedProfileForRead(userId)).thenReturn(Optional.of(profileId));
		var response = service.getSettings(userId);
		assertThat(response.visible()).isFalse();
		assertThat(response.version()).isZero();
		verify(settingsRepository, never()).saveAndFlush(any());
	}

	@Test
	void settingsCanOnlyResolveTheAuthenticatedOwnersProfile() {
		assertThatThrownBy(() -> service.updateSettings(userId, new MusicianCalendarSettingsUpdate(false, 0L)))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND));
		verify(settingsRepository).lockOwnedProfileForUpdate(userId);
		verify(settingsRepository, never()).findById(any());
		verify(settingsRepository, never()).saveAndFlush(any());
	}

	@Test
	void firstChangeCreatesOnlyTheSeparatePreferenceAndAdvancesVersion() {
		when(settingsRepository.lockOwnedProfileForUpdate(userId)).thenReturn(Optional.of(profileId));
		var response = service.updateSettings(userId, new MusicianCalendarSettingsUpdate(true, 0L));
		assertThat(response.visible()).isTrue();
		assertThat(response.version()).isEqualTo(1L);
		verify(settingsRepository).saveAndFlush(argThat(settings -> settings.getMusicianProfileId().equals(profileId)
				&& settings.isVisible() && settings.getVersion() == 1));
	}

	@Test
	void exactLostReplyRetryIsIdempotent() {
		current(false, 1);
		var response = service.updateSettings(userId, new MusicianCalendarSettingsUpdate(false, 0L));
		assertThat(response.version()).isEqualTo(1L);
		verify(settingsRepository, never()).saveAndFlush(any());
	}

	@Test
	void alreadyCurrentValueDoesNotConsumeARevision() {
		current(false, 3);
		assertThat(service.updateSettings(userId, new MusicianCalendarSettingsUpdate(false, 3L)).version()).isEqualTo(3L);
		verify(settingsRepository, never()).saveAndFlush(any());
	}

	@ParameterizedTest
	@CsvSource({"true,0", "false,0", "true,1", "false,3"})
	void staleOrFutureVersionCannotOverwriteAnotherDevice(boolean visible, long version) {
		current(false, 2);
		assertThatThrownBy(() -> service.updateSettings(userId, new MusicianCalendarSettingsUpdate(visible, version)))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT));
		verify(settingsRepository, never()).saveAndFlush(any());
	}

	@Test
	void emptyCalendarIsHiddenWithoutConsultingLegacySettings() {
		when(settingsRepository.lockProfileForRead(profileId)).thenReturn(Optional.of(profileId));
		when(eventRepository.findApprovedEventIds(profileId, start, start.plusDays(6), List.of(), PageRequest.of(0, 20)))
				.thenReturn(new SliceImpl<>(List.of()));
		var response = service.getCalendar(profileId, start, start.plusDays(6), 0, 20);
		assertThat(response.visible()).isFalse();
		assertThat(response.events()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.profileId()).isEqualTo(profileId);
		assertThat(response.startDate()).isEqualTo(start);
		assertThat(response.endDate()).isEqualTo(start.plusDays(6));
		verifyNoInteractions(eventMapper);
		verify(settingsRepository, never()).findById(any());
	}

	@Test
	void unknownPublicProfileReturns404RatherThanInventingAnEmptyProfile() {
		assertThatThrownBy(() -> service.getCalendar(profileId, start, start.plusDays(6), 0, 20))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND));
		verifyNoInteractions(eventRepository, eventMapper);
	}

	@Test
	void twoPhaseDetailsRetainChronologicalOrderAndSkipConcurrentRevocations() {
		List<UUID> bands = List.of(UUID.randomUUID(), UUID.randomUUID());
		when(settingsRepository.lockCalendarBandsForRead(profileId, start, start.plusDays(6), 129)).thenReturn(bands);
		when(settingsRepository.lockProfileForRead(profileId)).thenReturn(Optional.of(profileId));
		UUID first = UUID.randomUUID(), revoked = UUID.randomUUID(), last = UUID.randomUUID();
		var pageable = PageRequest.of(0, 3);
		when(eventRepository.findApprovedEventIds(profileId, start, start.plusDays(6), bands, pageable))
				.thenReturn(new SliceImpl<>(List.of(first, revoked, last), pageable, true));
		Event firstEvent = Event.builder().build(); firstEvent.setId(first);
		Event lastEvent = Event.builder().build(); lastEvent.setId(last);
		when(eventRepository.findCalendarDetails(List.of(first, revoked, last), profileId, bands)).thenReturn(List.of(lastEvent, firstEvent));
		var response = service.getCalendar(profileId, start, start.plusDays(6), 0, 3);
		assertThat(response.visible()).isTrue();
		assertThat(response.hasNext()).isTrue();
		var order = inOrder(eventMapper);
		order.verify(eventMapper).toDto(firstEvent);
		order.verify(eventMapper).toDto(lastEvent);
		verify(eventMapper, times(2)).toDto(any());
		var locks = inOrder(settingsRepository, eventRepository);
		locks.verify(settingsRepository).lockCalendarBandsForRead(profileId, start, start.plusDays(6), 129);
		locks.verify(settingsRepository).lockProfileForRead(profileId);
		locks.verify(eventRepository).findApprovedEventIds(profileId, start, start.plusDays(6), bands, pageable);
		locks.verify(eventRepository).findCalendarDetails(List.of(first, revoked, last), profileId, bands);
	}

	@Test
	void oversizedBandLockSetFailsClosedBeforeAnyEventQueryRatherThanSilentlyTruncating() {
		List<UUID> oversized = java.util.stream.IntStream.range(0, 129).mapToObj(i -> UUID.randomUUID()).toList();
		when(settingsRepository.lockCalendarBandsForRead(profileId, start, start, 129)).thenReturn(oversized);
		assertThatThrownBy(() -> service.getCalendar(profileId, start, start, 0, 20))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_QUERY_INVALID));
		verify(settingsRepository, never()).lockProfileForRead(any());
		verifyNoInteractions(eventRepository, eventMapper);
	}

	@Test
	void fullyRevokedSliceRetainsNextPageWithoutClaimingHiddenCalendar() {
		when(settingsRepository.lockProfileForRead(profileId)).thenReturn(Optional.of(profileId));
		var pageable = PageRequest.of(0, 20);
		when(eventRepository.findApprovedEventIds(profileId, start, start, List.of(), pageable))
				.thenReturn(new SliceImpl<>(List.of(UUID.randomUUID()), pageable, true));
		var response = service.getCalendar(profileId, start, start, 0, 20);
		assertThat(response.events()).isEmpty();
		assertThat(response.visible()).isTrue();
		assertThat(response.hasNext()).isTrue();
		verifyNoInteractions(eventMapper);
	}

	@ParameterizedTest
	@CsvSource({"-1,20,6", "101,20,6", "0,0,6", "0,51,6", "0,20,-1", "0,20,31"})
	void invalidOrUnboundedQueriesNeverReachTheDatabase(int page, int size, int days) {
		assertThatThrownBy(() -> service.getCalendar(profileId, start, start.plusDays(days), page, size))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_QUERY_INVALID));
		verifyNoInteractions(settingsRepository, eventRepository);
	}

	private void current(boolean visible, long version) {
		when(settingsRepository.lockOwnedProfileForUpdate(userId)).thenReturn(Optional.of(profileId));
		MusicianCalendarSettings settings = new MusicianCalendarSettings(profileId);
		settings.setVisible(visible); settings.setVersion(version);
		when(settingsRepository.findById(profileId)).thenReturn(Optional.of(settings));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0000-01-01", "+10000-01-01", "+999999999-01-01"})
	void yearsOutsideApiDateContractNeverReachPostgres(String isoDate) {
		LocalDate date = LocalDate.parse(isoDate);
		assertThatThrownBy(() -> service.getCalendar(profileId, date, date, 0, 20))
				.isInstanceOfSatisfying(SoundConnectException.class,
						e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_QUERY_INVALID));
		verifyNoInteractions(settingsRepository, eventRepository);
	}
}
