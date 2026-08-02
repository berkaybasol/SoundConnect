package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceOtpRegistrationTest {

	@Mock JwtTokenProvider jwtTokenProvider;
	@Mock UserRepository userRepository;
	@Mock PasswordEncoder passwordEncoder;
	@Mock RoleRepository roleRepository;
	@Mock ProfileFactory profileFactory;
	@Mock OtpService otpService;
	@Mock OtpMailService otpMailService;
	@Mock VenueApplicationService venueApplicationService;
	@Mock StudioApplicationService studioApplicationService;
	@Mock AuthAccountRateLimitGuard accountRateLimitGuard;
	@InjectMocks AuthService authService;

	@Test
	void registrationCanonicalizesUsernameBeforeLookupAndPersistence() {
		RegisterRequestDto request = new RegisterRequestDto(
				" BerKay ",
				"user@example.com",
				"password123",
				"password123",
				RoleEnum.ROLE_LISTENER,
				null, null, null, null, null, null
		);
		Role listenerRole = Role.builder().name(RoleEnum.ROLE_LISTENER.name()).build();
		when(roleRepository.findByName(RoleEnum.ROLE_LISTENER.name())).thenReturn(Optional.of(listenerRole));
		when(passwordEncoder.encode("password123")).thenReturn("encoded");
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(otpService.acquireInitialOtp("user@example.com"))
				.thenReturn(new OtpService.OtpIssueClaim(false, null, 10L));

		authService.register(request);

		ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).existsByUsername("berkay");
		verify(userRepository).saveAndFlush(userCaptor.capture());
		assertThat(userCaptor.getValue().getUsername()).isEqualTo("berkay");
	}

	@Test
	void registrationMapsConcurrentCanonicalUsernameConflict() {
		RegisterRequestDto request = new RegisterRequestDto(
				" BeRKay ",
				"user@example.com",
				"password123",
				"password123",
				RoleEnum.ROLE_LISTENER,
				null, null, null, null, null, null
		);
		when(roleRepository.findByName(RoleEnum.ROLE_LISTENER.name()))
				.thenReturn(Optional.of(Role.builder().name(RoleEnum.ROLE_LISTENER.name()).build()));
		when(passwordEncoder.encode("password123")).thenReturn("encoded");
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint ux_tbl_user_username_canonical; Key (user_name)"
		));

		assertThatThrownBy(() -> authService.register(request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.USER_ALREADY_EXISTS));

		verify(profileFactory, never()).createProfileIfNeeded(any(), any());
		verify(otpService, never()).acquireInitialOtp(any());
	}

	@Test
	void initialRegistrationClaimPreventsASecondConcurrentMailAndCode() {
		RegisterRequestDto request = new RegisterRequestDto(
				"listener",
				"User@Example.com",
				"password123",
				"password123",
				RoleEnum.ROLE_LISTENER,
				null,
				null,
				null,
				null,
				null,
				null
		);
		Role listenerRole = Role.builder().name(RoleEnum.ROLE_LISTENER.name()).build();
		OtpService.OtpIssueClaim winner = new OtpService.OtpIssueClaim(true, "123456", 0L);
		OtpService.OtpIssueClaim loser = new OtpService.OtpIssueClaim(false, null, 29L);
		when(roleRepository.findByName(RoleEnum.ROLE_LISTENER.name())).thenReturn(Optional.of(listenerRole));
		when(passwordEncoder.encode("password123")).thenReturn("encoded");
		when(otpService.acquireInitialOtp("user@example.com")).thenReturn(winner, loser);
		when(otpService.getOtpTimeLeftSeconds("user@example.com")).thenReturn(180L);

		BaseResponse<RegisterResponseDto> first = authService.register(request);
		BaseResponse<RegisterResponseDto> second = authService.register(request);

		assertThat(first.getData().mailQueued()).isTrue();
		assertThat(second.getData().mailQueued()).isFalse();
		verify(otpMailService, times(1)).sendVerificationMail("user@example.com", "123456");
		verify(otpService, times(2)).acquireInitialOtp("user@example.com");
	}

	@Test
	void venueRegistrationWithoutNeighborhoodIsRejectedBeforeUserCreation() {
		RegisterRequestDto request = new RegisterRequestDto(
				"venue-owner",
				"venue@example.com",
				"password123",
				"password123",
				RoleEnum.ROLE_VENUE,
				"Venue",
				"Address",
				"05551234567",
				UUID.randomUUID().toString(),
				UUID.randomUUID().toString(),
				null
		);

		assertThatThrownBy(() -> authService.register(request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		verify(userRepository, never()).saveAndFlush(any());
		verify(venueApplicationService, never()).createApplication(any(), any());
	}

	@Test
	void studioRegistrationCreatesPendingApplicationWithoutRoleOrProfile() {
		UUID cityId = UUID.randomUUID();
		UUID districtId = UUID.randomUUID();
		UUID neighborhoodId = UUID.randomUUID();
		RegisterRequestDto request = new RegisterRequestDto(
				"studio-owner", "studio@example.com", "password123", "password123",
				RoleEnum.ROLE_STUDIO, null, null, null,
				cityId.toString(), districtId.toString(), neighborhoodId.toString(),
				"Devo Studio", "Moda Caddesi", "05551234567"
		);
		UUID userId = UUID.randomUUID();
		UUID applicationId = UUID.randomUUID();
		when(passwordEncoder.encode("password123")).thenReturn("encoded");
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			user.setId(userId);
			return user;
		});
		when(otpService.acquireInitialOtp("studio@example.com"))
				.thenReturn(new OtpService.OtpIssueClaim(true, "123456", 0L));
		when(otpService.getOtpTimeLeftSeconds("studio@example.com")).thenReturn(180L);
		when(studioApplicationService.createApplication(any(), any())).thenReturn(
				new StudioApplicationResponseDto(
						applicationId, userId, "studio-owner", "Devo Studio", "Moda Caddesi",
						"05551234567", cityId, "Istanbul", districtId, "Kadikoy",
						neighborhoodId, "Moda", ApplicationStatus.PENDING, null, null, null, null
				)
		);

		BaseResponse<RegisterResponseDto> response = authService.register(request);

		assertThat(response.getData().status()).isEqualTo(UserStatus.PENDING_STUDIO_REQUEST);
		assertThat(response.getData().applicationId()).isEqualTo(applicationId);
		ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(userCaptor.capture());
		assertThat(userCaptor.getValue().getRoles()).isEmpty();
		assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.PENDING_STUDIO_REQUEST);
		ArgumentCaptor<StudioApplicationCreateRequestDto> applicationCaptor =
				ArgumentCaptor.forClass(StudioApplicationCreateRequestDto.class);
		verify(studioApplicationService).createApplication(org.mockito.ArgumentMatchers.eq(userId), applicationCaptor.capture());
		assertThat(applicationCaptor.getValue().studioName()).isEqualTo("Devo Studio");
		assertThat(applicationCaptor.getValue().neighborhoodId()).isEqualTo(neighborhoodId.toString());
		verify(profileFactory, never()).createProfileIfNeeded(any(), any());
	}
}
