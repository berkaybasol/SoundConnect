package com.berkayb.soundconnect.tools.simulation.seed.account;

import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationOtpCaptureMailService;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationResolvedLocation;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationAccountRegistrationOrchestratorTest {

	private static final String PASSWORD = "SimulationOnly!2026";
	private static final String HASH = "encoded-password";
	private static final String OTP = "123456";

	@Mock
	private SimulationRuntimeGuard runtimeGuard;
	@Mock
	private Validator validator;
	@Mock
	private AuthService authService;
	@Mock
	private SimulationOtpCaptureMailService otpCaptureMailService;
	@Mock
	private VenueApplicationService venueApplicationService;
	@Mock
	private StudioApplicationService studioApplicationService;
	@Mock
	private UserRepository userRepository;
	@Mock
	private PasswordEncoder passwordEncoder;

	private SimulationResolvedLocation location;
	private SimulationControlAdmin controlAdmin;

	@BeforeEach
	void setUp() {
		City city = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Kadıköy").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).name("Caferağa").district(district).build();
		location = new SimulationResolvedLocation(
				city, district, neighborhood, "Caferağa Mah. Nota Sok. No: 8");
		controlAdmin = new SimulationControlAdmin(
				UUID.randomUUID(), "simulation_control_admin",
				"control-admin@soundconnect.invalid", false);
	}

	@Test
	void verifiedMusicianUsesRealRegisterAndOtpServices() {
		Account account = account(AccountRole.MUSICIAN, EmailVerificationState.VERIFIED, null);
		User finalUser = finalUser(account, UserStatus.ACTIVE, RoleEnum.ROLE_MUSICIAN, true);
		stubNewRegistration(account, UserStatus.INACTIVE, null, finalUser);

		SimulationRegisteredAccount result = orchestrator().materialize(
				account, location, PASSWORD, controlAdmin, false);

		assertThat(result.userId()).isEqualTo(finalUser.getId());
		assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
		assertThat(result.emailVerified()).isTrue();
		assertThat(result.created()).isTrue();
		verify(authService).register(any(RegisterRequestDto.class));
		ArgumentCaptor<VerifyCodeRequestDto> verification =
				ArgumentCaptor.forClass(VerifyCodeRequestDto.class);
		verify(authService).verifyCode(verification.capture());
		assertThat(verification.getValue())
				.isEqualTo(new VerifyCodeRequestDto(account.email(), OTP));
		verify(venueApplicationService, never()).approveApplication(any(), any());
		verify(studioApplicationService, never()).approveApplication(any(), any());
	}

	@Test
	void designatedMusicianRemainsUnverifiedWithoutLeakingCapturedOtp() {
		Account account = account(AccountRole.MUSICIAN, EmailVerificationState.UNVERIFIED, null);
		User finalUser = finalUser(account, UserStatus.INACTIVE, RoleEnum.ROLE_MUSICIAN, false);
		stubNewRegistration(account, UserStatus.INACTIVE, null, finalUser);

		SimulationRegisteredAccount result = orchestrator().materialize(
				account, location, PASSWORD, null, false);

		assertThat(result.status()).isEqualTo(UserStatus.INACTIVE);
		assertThat(result.emailVerified()).isFalse();
		assertThat(finalUser.getRoles())
				.singleElement()
				.extracting(Role::getName)
				.isEqualTo(RoleEnum.ROLE_MUSICIAN.name());
		verify(otpCaptureMailService).consume(account.email());
		verify(authService, never()).verifyCode(any());
	}

	@Test
	void approvedVenueMapsInstitutionFieldsAndUsesRealApprovalService() {
		Account account = account(
				AccountRole.VENUE, EmailVerificationState.VERIFIED, InstitutionState.APPROVED);
		UUID applicationId = UUID.randomUUID();
		User finalUser = finalUser(account, UserStatus.ACTIVE, RoleEnum.ROLE_VENUE, true);
		stubNewRegistration(account, UserStatus.PENDING_VENUE_REQUEST, applicationId, finalUser);

		SimulationRegisteredAccount result = orchestrator().materialize(
				account, location, PASSWORD, controlAdmin, false);

		assertThat(result.applicationId()).isEqualTo(applicationId);
		ArgumentCaptor<RegisterRequestDto> request = ArgumentCaptor.forClass(RegisterRequestDto.class);
		verify(authService).register(request.capture());
		assertThat(request.getValue().role()).isEqualTo(RoleEnum.ROLE_VENUE);
		assertThat(request.getValue().venueName()).isEqualTo(account.displayName());
		assertThat(request.getValue().venueAddress()).isEqualTo(location.addressLine());
		assertThat(request.getValue().phone()).isEqualTo(account.contactPhone());
		assertThat(request.getValue().cityId()).isEqualTo(location.city().getId().toString());
		verify(venueApplicationService).approveApplication(applicationId, controlAdmin.userId());
	}

	@Test
	void rejectedStudioUsesRealRejectionServiceAndKeepsExactFinalState() {
		Account account = account(
				AccountRole.STUDIO, EmailVerificationState.VERIFIED, InstitutionState.REJECTED);
		UUID applicationId = UUID.randomUUID();
		User finalUser = finalUser(account, UserStatus.REJECTED_STUDIO_REQUEST, null, true);
		stubNewRegistration(account, UserStatus.PENDING_STUDIO_REQUEST, applicationId, finalUser);

		SimulationRegisteredAccount result = orchestrator().materialize(
				account, location, PASSWORD, controlAdmin, false);

		assertThat(result.status()).isEqualTo(UserStatus.REJECTED_STUDIO_REQUEST);
		verify(studioApplicationService).rejectApplication(
				org.mockito.ArgumentMatchers.eq(applicationId),
				org.mockito.ArgumentMatchers.eq(controlAdmin.userId()),
				org.mockito.ArgumentMatchers.contains("intentionally rejected"));
		verify(studioApplicationService, never()).approveApplication(any(), any());
	}

	@Test
	void resumeReturnsExactCompletedAccountWithoutReplayingLifecycle() {
		Account account = account(AccountRole.LISTENER, EmailVerificationState.VERIFIED, null);
		User existing = finalUser(account, UserStatus.ACTIVE, RoleEnum.ROLE_LISTENER, true);
		when(validator.validate(any(RegisterRequestDto.class))).thenReturn(Set.of());
		when(userRepository.findByUsername(account.username())).thenReturn(Optional.of(existing));
		when(userRepository.findByEmail(account.email())).thenReturn(Optional.of(existing));
		when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

		SimulationRegisteredAccount result = orchestrator().materialize(
				account, location, PASSWORD, null, true);

		assertThat(result.created()).isFalse();
		assertThat(result.userId()).isEqualTo(existing.getId());
		verify(authService, never()).register(any());
		verify(authService, never()).verifyCode(any());
		verify(otpCaptureMailService, never()).consume(any());
	}

	@Test
	void resumeReadsPendingInstitutionApplicationWithoutDecisionReplay() {
		Account account = account(
				AccountRole.VENUE, EmailVerificationState.VERIFIED, InstitutionState.PENDING);
		User existing = finalUser(account, UserStatus.PENDING_VENUE_REQUEST, null, true);
		UUID applicationId = UUID.randomUUID();
		when(validator.validate(any(RegisterRequestDto.class))).thenReturn(Set.of());
		when(userRepository.findByUsername(account.username())).thenReturn(Optional.of(existing));
		when(userRepository.findByEmail(account.email())).thenReturn(Optional.of(existing));
		when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
		when(venueApplicationService.getPendingApplicationByUser(existing.getId()))
				.thenReturn(new VenueApplicationResponseDto(
						applicationId, account.username(), account.displayName(), location.addressLine(),
						account.contactPhone(), ApplicationStatus.PENDING,
						LocalDateTime.now(), null));

		SimulationRegisteredAccount result = orchestrator().materialize(
				account, location, PASSWORD, null, true);

		assertThat(result.applicationId()).isEqualTo(applicationId);
		verify(venueApplicationService, never()).approveApplication(any(), any());
		verify(authService, never()).register(any());
	}

	@Test
	void existingIdentityIsRejectedWhenResumeWasNotRequested() {
		Account account = account(AccountRole.MUSICIAN, EmailVerificationState.VERIFIED, null);
		User existing = finalUser(account, UserStatus.ACTIVE, RoleEnum.ROLE_MUSICIAN, true);
		when(validator.validate(any(RegisterRequestDto.class))).thenReturn(Set.of());
		when(userRepository.findByUsername(account.username())).thenReturn(Optional.of(existing));
		when(userRepository.findByEmail(account.email())).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> orchestrator().materialize(
				account, location, PASSWORD, null, false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not allow resume");

		verify(authService, never()).register(any());
	}

	@Test
	void resumeFailsClosedForConflictingLifecycle() {
		Account account = account(AccountRole.MUSICIAN, EmailVerificationState.VERIFIED, null);
		User existing = finalUser(account, UserStatus.INACTIVE, RoleEnum.ROLE_MUSICIAN, false);
		when(validator.validate(any(RegisterRequestDto.class))).thenReturn(Set.of());
		when(userRepository.findByUsername(account.username())).thenReturn(Optional.of(existing));
		when(userRepository.findByEmail(account.email())).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> orchestrator().materialize(
				account, location, PASSWORD, null, true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("guarded FRESH");

		verify(authService, never()).register(any());
	}

	@Test
	void explicitJakartaValidationRunsBeforeRegistration() {
		Account account = account(AccountRole.MUSICIAN, EmailVerificationState.VERIFIED, null);
		@SuppressWarnings("unchecked")
		ConstraintViolation<RegisterRequestDto> violation = org.mockito.Mockito.mock(ConstraintViolation.class);
		when(validator.validate(any(RegisterRequestDto.class))).thenReturn(Set.of(violation));

		assertThatThrownBy(() -> orchestrator().materialize(
				account, location, PASSWORD, null, false))
				.isInstanceOf(ConstraintViolationException.class)
				.hasMessageContaining("Invalid simulation registration DTO")
				.hasMessageContaining(account.key());

		verify(authService, never()).register(any());
		verify(userRepository, never()).findByUsername(any());
	}

	@Test
	void unverifiedNonMusicianIsRejectedBeforeAnySideEffect() {
		Account account = account(AccountRole.LISTENER, EmailVerificationState.UNVERIFIED, null);

		assertThatThrownBy(() -> orchestrator().materialize(
				account, location, PASSWORD, null, false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Only a musician");

		verify(authService, never()).register(any());
		verify(userRepository, never()).findByUsername(any());
	}

	private void stubNewRegistration(
			Account account,
			UserStatus initialStatus,
			UUID applicationId,
			User finalUser
	) {
		when(validator.validate(any(RegisterRequestDto.class))).thenReturn(Set.of());
		if (account.emailVerification() == EmailVerificationState.VERIFIED) {
			when(validator.validate(any(VerifyCodeRequestDto.class))).thenReturn(Set.of());
			when(authService.verifyCode(any(VerifyCodeRequestDto.class)))
					.thenReturn(success(200, null));
		}
		when(userRepository.findByUsername(account.username())).thenReturn(Optional.empty());
		when(userRepository.findByEmail(account.email()))
				.thenReturn(Optional.empty(), Optional.of(finalUser));
		when(authService.register(any(RegisterRequestDto.class))).thenReturn(success(201,
				new RegisterResponseDto(account.email(), initialStatus, 180, true, applicationId)));
		when(otpCaptureMailService.consume(account.email())).thenReturn(Optional.of(OTP));
		when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
	}

	private <T> BaseResponse<T> success(int code, T data) {
		return BaseResponse.<T>builder().success(true).code(code).data(data).build();
	}

	private SimulationAccountRegistrationOrchestrator orchestrator() {
		return new SimulationAccountRegistrationOrchestrator(
				runtimeGuard, validator, authService, otpCaptureMailService,
				venueApplicationService, studioApplicationService, userRepository, passwordEncoder);
	}

	private Account account(
			AccountRole role,
			EmailVerificationState verification,
			InstitutionState institutionState
	) {
		String suffix = role.name().toLowerCase();
		return new Account(
				"test-" + suffix,
				role,
				SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK,
				"test_" + suffix,
				"test-" + suffix + "@soundconnect.invalid",
				verification,
				"Test",
				"Kullanıcı",
				role == AccountRole.VENUE ? "Test Sahne"
						: role == AccountRole.STUDIO ? "Test Stüdyo" : "Test Kullanıcı",
				"Test hesabı için yeterince uzun ve güvenli bir profil açıklaması.",
				"Deterministik test senaryosunda kullanılan sentetik hesap.",
				role == AccountRole.MUSICIAN ? List.of("Gitar") : List.of(),
				role == AccountRole.MUSICIAN
						? new SimulationWorldManifest.MusicianProfilePlan(true, true, true, true, true, true)
						: null,
				SimulationWorldManifest.ObserverProfile.NONE,
				role == AccountRole.LISTENER
						? SimulationWorldManifest.ListenerVisibilityState.STANDARD : null,
				institutionState,
				new SimulationWorldManifest.Location(
						"İstanbul", "Kadıköy", "Caferağa",
						role == AccountRole.VENUE || role == AccountRole.STUDIO
								? location.addressLine() : null),
				role == AccountRole.VENUE || role == AccountRole.STUDIO
						? "+90 555 000 00 01" : null
		);
	}

	private User finalUser(
			Account account,
			UserStatus status,
			RoleEnum role,
			boolean verified
	) {
		Set<Role> roles = role == null
				? Set.of()
				: Set.of(Role.builder().name(role.name()).build());
		return User.builder()
				.id(UUID.randomUUID())
				.username(account.username())
				.email(account.email())
				.password(HASH)
				.provider(AuthProvider.LOCAL)
				.status(status)
				.emailVerified(verified)
				.roles(roles)
				.build();
	}
}
