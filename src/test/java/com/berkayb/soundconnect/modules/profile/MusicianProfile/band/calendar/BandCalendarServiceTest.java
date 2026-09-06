package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar;

import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BandCalendarServiceTest {
	@Mock BandCalendarSettingsRepository settings;
	@Mock BandCalendarEventRepository events;
	@Mock EventMapper mapper;
	@InjectMocks BandCalendarService service;
	private final UUID bandId = UUID.randomUUID(), userId = UUID.randomUUID();
	private final LocalDate date = LocalDate.of(2026, 9, 5);

	@Test void emptyCalendarIsHiddenWithoutConsultingLegacyPreference() {
		when(settings.lockBandForRead(bandId)).thenReturn(Optional.of(bandId));
		when(events.findApprovedEventIds(bandId, date, date.plusDays(6), org.springframework.data.domain.PageRequest.of(0, 20)))
				.thenReturn(new org.springframework.data.domain.SliceImpl<>(java.util.List.of()));
		var page = service.getCalendar(bandId, date, date.plusDays(6), 0, 20);
		assertThat(page.visible()).isFalse();
		assertThat(page.events()).isEmpty();
		assertThat(page.hasNext()).isFalse();
		verifyNoInteractions(mapper);
		verify(settings, never()).findById(any());
		verify(settings, never()).saveAndFlush(any());
	}

	@Test void settingsAreOptedOutWithoutCreatingRows() {
		when(settings.lockBandForRead(bandId)).thenReturn(Optional.of(bandId));
		when(settings.lockActiveFounder(bandId, userId)).thenReturn(Optional.of(UUID.randomUUID()));
		assertThat(service.getSettings(bandId, userId).visible()).isFalse();
		assertThat(service.getSettings(bandId, userId).version()).isZero();
		verify(settings, never()).saveAndFlush(any());
	}

	@Test void fullyRevokedSliceKeepsPaginationContractCoherent() {
		when(settings.lockBandForRead(bandId)).thenReturn(Optional.of(bandId));
		var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
		when(events.findApprovedEventIds(bandId, date, date, pageable)).thenReturn(
				new org.springframework.data.domain.SliceImpl<>(java.util.List.of(UUID.randomUUID()), pageable, true));
		var response = service.getCalendar(bandId, date, date, 0, 20);
		assertThat(response.events()).isEmpty();
		assertThat(response.visible()).isTrue();
		assertThat(response.hasNext()).isTrue();
		verifyNoInteractions(mapper);
	}

	@Test void outsiderCannotReadOrWritePreferences() {
		when(settings.lockBandForRead(bandId)).thenReturn(Optional.of(bandId));
		when(settings.lockBandForUpdate(bandId)).thenReturn(Optional.of(bandId));
		assertForbidden(() -> service.getSettings(bandId, userId));
		assertForbidden(() -> service.updateSettings(bandId, userId, new MusicianCalendarSettingsUpdate(true, 0L)));
		verify(settings, never()).findById(any());
		verify(settings, never()).saveAndFlush(any());
	}

	@Test void earlyAuthorizationDoesNotReplaceLockedAuthorization() {
		when(settings.isActiveFounder(bandId, userId)).thenReturn(true);
		service.requireFounder(bandId, userId);
		when(settings.lockBandForUpdate(bandId)).thenReturn(Optional.of(bandId));
		assertForbidden(() -> service.updateSettings(bandId, userId, new MusicianCalendarSettingsUpdate(true, 0L)));
		verify(settings, never()).saveAndFlush(any());
	}

	@Test void outsiderCannotSpendTheSharedBudget() { assertForbidden(() -> service.requireFounder(bandId, userId)); }

	@Test void enableAdvancesVersionAndSameLostReplyIsIdempotent() {
		authorized();
		var response = service.updateSettings(bandId, userId, new MusicianCalendarSettingsUpdate(true, 0L));
		assertThat(response.visible()).isTrue();
		assertThat(response.version()).isEqualTo(1);
		BandCalendarSettings saved = new BandCalendarSettings(bandId);
		saved.setVisible(true); saved.setVersion(1);
		when(settings.findById(bandId)).thenReturn(Optional.of(saved));
		assertThat(service.updateSettings(bandId, userId, new MusicianCalendarSettingsUpdate(true, 0L))).isEqualTo(response);
		verify(settings, times(1)).saveAndFlush(any());
	}

	@Test void staleOtherChoiceCannotOverwriteTheCurrentSetting() {
		authorized();
		BandCalendarSettings saved = new BandCalendarSettings(bandId); saved.setVersion(2);
		when(settings.findById(bandId)).thenReturn(Optional.of(saved));
		assertThatThrownBy(() -> service.updateSettings(bandId, userId, new MusicianCalendarSettingsUpdate(true, 0L)))
				.isInstanceOfSatisfying(SoundConnectException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT));
		verify(settings, never()).saveAndFlush(any());
	}

	@ParameterizedTest @CsvSource({"-1,20,6", "101,20,6", "0,0,6", "0,51,6", "0,20,31", "0,20,-1"})
	void rejectsUnboundedQueriesBeforeDatabase(int page, int size, int days) {
		assertThatThrownBy(() -> service.getCalendar(bandId, date, date.plusDays(days), page, size))
				.isInstanceOfSatisfying(SoundConnectException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_QUERY_INVALID));
		verifyNoInteractions(settings, events);
	}

	private void authorized() {
		when(settings.lockBandForUpdate(bandId)).thenReturn(Optional.of(bandId));
		when(settings.lockActiveFounder(bandId, userId)).thenReturn(Optional.of(UUID.randomUUID()));
	}
	private void assertForbidden(ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
				e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
	}
}
