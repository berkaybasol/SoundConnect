package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleBusinessApplicationsTest {
	private static ValidatorFactory factory;
	private final VenueApplicationService venues = mock(VenueApplicationService.class);
	private final StudioApplicationService studios = mock(StudioApplicationService.class);
	private final VenueApplicationSessionAccess venueSessions = mock(VenueApplicationSessionAccess.class);
	private final JwtTokenProvider tokens = mock(JwtTokenProvider.class);
	private final UUID applicationId = UUID.randomUUID();
	private final String city = UUID.randomUUID().toString();
	private final String district = UUID.randomUUID().toString();
	private final String neighborhood = UUID.randomUUID().toString();

	@BeforeAll static void openValidator() { factory = Validation.buildDefaultValidatorFactory(); }
	@AfterAll static void closeValidator() { factory.close(); }

	private GoogleBusinessApplications service() {
		return new GoogleBusinessApplications(venues, studios, venueSessions, tokens, factory.getValidator());
	}

	@Test void venueUsesOrdinaryApplicationAndIssuesOnlyItsRestrictedSession() {
		User user = applicant(UserStatus.PENDING_VENUE_REQUEST);
		var form = new GoogleCompleteProfileRequestDto("proof", "handle", RoleEnum.ROLE_VENUE, true,
				"Venue name", "Venue address", "05001234567", null, null, null, city, district, neighborhood);
		var created = mock(VenueApplicationResponseDto.class);
		when(created.id()).thenReturn(applicationId);
		when(venues.createApplication(user.getId(), new VenueApplicationCreateRequestDto("Venue name", "Venue address",
				"05001234567", city, district, neighborhood))).thenReturn(created);
		when(tokens.generateVenueApplicationToken(any(), eq(applicationId))).thenReturn("restricted");
		when(venueSessions.findSessionApplication(user)).thenReturn(Optional.of(applicationId));

		var initial = service().submit(user, form);
		assertThat(initial).isEqualTo(service().resume(user));
		assertThat(initial.token()).isEqualTo("restricted");
		assertThat(initial.applicationId()).isEqualTo(applicationId);
		assertThat(initial.sessionScope()).isEqualTo("VENUE_APPLICATION");
		assertThat(initial.roles()).isEmpty();
		assertThat(initial.permissions()).isEmpty();
		assertThat(initial.requiresGoogleProfileCompletion()).isFalse();
		verify(tokens, never()).generateToken(any());
		verifyNoInteractions(studios);
	}

	@Test void studioConfirmationAndLaterSignInReturnTheApplicationWithoutABearer() {
		User user = applicant(UserStatus.PENDING_STUDIO_REQUEST);
		var form = new GoogleCompleteProfileRequestDto("proof", "handle", RoleEnum.ROLE_STUDIO, true,
				null, null, null, "Studio name", "Studio address", "05001234567", city, district, neighborhood);
		var record = mock(StudioApplicationResponseDto.class);
		when(record.id()).thenReturn(applicationId);
		when(studios.createApplication(user.getId(), new StudioApplicationCreateRequestDto("Studio name",
				"Studio address", "05001234567", city, district, neighborhood))).thenReturn(record);
		when(studios.getPendingApplicationByUser(user.getId())).thenReturn(record);
		var initial = service().submit(user, form);
		assertThat(initial).isEqualTo(service().resume(user));
		assertThat(initial.status()).isEqualTo(UserStatus.PENDING_STUDIO_REQUEST);
		assertThat(initial.applicationId()).isEqualTo(applicationId);
		assertThat(initial.token()).isNull();
		assertThat(initial.roles()).isEmpty();
		assertThat(initial.permissions()).isEmpty();
		assertThat(initial.requiresGoogleProfileCompletion()).isFalse();
		user.setStatus(UserStatus.REJECTED_STUDIO_REQUEST);
        when(studios.rejectionForApplicant(user.getId())).thenReturn(
                new com.berkayb.soundconnect.shared.exception.StudioApplicationRejectedException("Studio name", "Adres eksik."));
		assertThatThrownBy(() -> service().resume(user)).isInstanceOfSatisfying(SoundConnectException.class,
				failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.STUDIO_APPLICATION_REJECTED));
		verifyNoInteractions(venues, venueSessions, tokens);
	}

	@ParameterizedTest @EnumSource(value = RoleEnum.class, names = {"ROLE_VENUE","ROLE_STUDIO"})
	void incompleteOrIncorrectFormCannotCreateAnApplication(RoleEnum role) {
		User user = applicant(role == RoleEnum.ROLE_VENUE ? UserStatus.PENDING_VENUE_REQUEST : UserStatus.PENDING_STUDIO_REQUEST);
		var form = new GoogleCompleteProfileRequestDto("proof", "name", role, true,
				"Stage", "Address", "555", "Studio", null, "000bad", city, null, neighborhood);
		assertThatThrownBy(() -> service().submit(user, form)).isInstanceOfSatisfying(SoundConnectException.class,
				failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		verifyNoInteractions(tokens, studios, venues, venueSessions);
	}

	@Test void inconsistentPendingAccountCannotAcquireAnyToken() {
		var user = applicant(UserStatus.PENDING_VENUE_REQUEST);
		assertThatThrownBy(() -> service().resume(user)).isInstanceOfSatisfying(SoundConnectException.class,
				failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.PENDING_VENUE_APPROVAL));
		user.getRoles().add(Role.builder().name("ROLE_MUSICIAN").build());
		assertThatThrownBy(() -> service().resume(user)).isInstanceOfSatisfying(SoundConnectException.class,
				failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
		verifyNoInteractions(tokens, studios, venues);
	}

	private static User applicant(UserStatus status) {
		return User.builder().id(UUID.randomUUID()).username("handle").status(status).emailVerified(true)
				.provider(AuthProvider.GOOGLE).build();
	}
}
