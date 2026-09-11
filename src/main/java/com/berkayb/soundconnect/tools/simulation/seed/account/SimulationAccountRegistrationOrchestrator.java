package com.berkayb.soundconnect.tools.simulation.seed.account;

import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationOtpCaptureMailService;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationResolvedLocation;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Drives synthetic accounts through the same registration, OTP and institution
 * review services used by real clients. It never writes account lifecycle state
 * directly. Resume accepts only an already-complete, exact identity; partial or
 * conflicting state fails closed and must be rebuilt with a guarded fresh run.
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SimulationAccountRegistrationOrchestrator {

	private static final String STUDIO_REJECTION_REASON =
			"Local simulation scenario: application intentionally rejected.";

	private final SimulationRuntimeGuard runtimeGuard;
	private final Validator validator;
	private final AuthService authService;
	private final SimulationOtpCaptureMailService otpCaptureMailService;
	private final VenueApplicationService venueApplicationService;
	private final StudioApplicationService studioApplicationService;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	public SimulationAccountRegistrationOrchestrator(
			SimulationRuntimeGuard runtimeGuard,
			Validator validator,
			AuthService authService,
			SimulationOtpCaptureMailService otpCaptureMailService,
			VenueApplicationService venueApplicationService,
			StudioApplicationService studioApplicationService,
			UserRepository userRepository,
			PasswordEncoder passwordEncoder
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.validator = Objects.requireNonNull(validator, "validator");
		this.authService = Objects.requireNonNull(authService, "authService");
		this.otpCaptureMailService = Objects.requireNonNull(otpCaptureMailService, "otpCaptureMailService");
		this.venueApplicationService = Objects.requireNonNull(venueApplicationService, "venueApplicationService");
		this.studioApplicationService = Objects.requireNonNull(studioApplicationService, "studioApplicationService");
		this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
		this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
	}

	/**
	 * Materializes one account. {@code allowExisting=true} is intended for RESUME
	 * and returns an exact completed lifecycle without replaying OTP or decisions.
	 */
	public SimulationRegisteredAccount materialize(
			Account account,
			SimulationResolvedLocation location,
			String commonPassword,
			SimulationControlAdmin controlAdmin,
			boolean allowExisting
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(location, "location");
		if (commonPassword == null || commonPassword.isBlank()) {
			throw new IllegalArgumentException("Simulation common password is required");
		}
		assertSupportedLifecycle(account);
		assertDecisionAdminAvailable(account, controlAdmin);

		RegisterRequestDto registration = registrationRequest(account, location, commonPassword);
		validate(registration, "registration", account.key());

		Optional<User> existingByUsername = userRepository.findByUsername(account.username());
		Optional<User> existingByEmail = userRepository.findByEmail(account.email());
		if (existingByUsername.isPresent() || existingByEmail.isPresent()) {
			if (!allowExisting) {
				throw new IllegalStateException(
						"Simulation account already exists and this run does not allow resume: " + account.key());
			}
			User existing = resolveSingleExistingIdentity(account, existingByUsername, existingByEmail);
			assertCompletedLifecycle(account, existing, commonPassword);
			return result(account, existing, resumedApplicationId(account, existing), false);
		}

		BaseResponse<RegisterResponseDto> registrationResponse = authService.register(registration);
		RegisterResponseDto registered = requireRegistrationResponse(account, registrationResponse);
		String otpCode = otpCaptureMailService.consume(account.email())
				.orElseThrow(() -> new IllegalStateException(
						"Simulation registration did not capture an OTP: " + account.key()));

		if (account.emailVerification() == EmailVerificationState.VERIFIED) {
			VerifyCodeRequestDto verification = new VerifyCodeRequestDto(account.email(), otpCode);
			validate(verification, "OTP verification", account.key());
			requireSuccessfulResponse(account, "OTP verification", 200, authService.verifyCode(verification));
		}

		applyInstitutionDecision(account, registered.applicationId(), controlAdmin);
		User materialized = userRepository.findByEmail(account.email())
				.orElseThrow(() -> new IllegalStateException(
						"Simulation account disappeared after registration: " + account.key()));
		assertCompletedLifecycle(account, materialized, commonPassword);
		return result(account, materialized, registered.applicationId(), true);
	}

	private RegisterRequestDto registrationRequest(
			Account account,
			SimulationResolvedLocation location,
			String commonPassword
	) {
		RoleEnum role = mappedRole(account.role());
		String cityId = requiredId(location.city().getId(), "city", account.key());
		String districtId = requiredId(location.district().getId(), "district", account.key());
		String neighborhoodId = requiredId(location.neighborhood().getId(), "neighborhood", account.key());

		if (account.role() == AccountRole.VENUE) {
			return new RegisterRequestDto(
					account.username(), account.email(), commonPassword, commonPassword, role,
					account.displayName(), requiredAddress(location, account), account.contactPhone(),
					cityId, districtId, neighborhoodId,
					null, null, null
			);
		}
		if (account.role() == AccountRole.STUDIO) {
			return new RegisterRequestDto(
					account.username(), account.email(), commonPassword, commonPassword, role,
					null, null, null,
					cityId, districtId, neighborhoodId,
					account.displayName(), requiredAddress(location, account), account.contactPhone()
			);
		}
		return new RegisterRequestDto(
				account.username(), account.email(), commonPassword, commonPassword, role,
				null, null, null, null, null, null,
				null, null, null
		);
	}

	private RegisterResponseDto requireRegistrationResponse(
			Account account,
			BaseResponse<RegisterResponseDto> response
	) {
		requireSuccessfulResponse(account, "registration", 201, response);
		RegisterResponseDto data = response.getData();
		if (data == null
				|| !account.email().equals(data.email())
				|| data.status() != initialStatus(account.role())
				|| !data.mailQueued()) {
			throw new IllegalStateException(
					"Simulation registration returned an unexpected lifecycle response: " + account.key());
		}
		boolean institution = isInstitution(account.role());
		if (institution != (data.applicationId() != null)) {
			throw new IllegalStateException(
					"Simulation registration returned an unexpected application id: " + account.key());
		}
		return data;
	}

	private void applyInstitutionDecision(
			Account account,
			UUID applicationId,
			SimulationControlAdmin controlAdmin
	) {
		if (!isInstitution(account.role()) || account.institutionState() == InstitutionState.PENDING) {
			return;
		}
		if (applicationId == null) {
			throw new IllegalStateException("Institution registration did not produce an application id");
		}
		if (controlAdmin == null || controlAdmin.userId() == null) {
			throw new IllegalArgumentException(
					"Simulation control admin is required for institution decisions");
		}

		if (account.role() == AccountRole.VENUE) {
			if (account.institutionState() != InstitutionState.APPROVED) {
				throw new IllegalArgumentException("Rejected venues are not supported by the simulation contract");
			}
			venueApplicationService.approveApplication(applicationId, controlAdmin.userId());
			return;
		}
		if (account.institutionState() == InstitutionState.APPROVED) {
			studioApplicationService.approveApplication(applicationId, controlAdmin.userId());
		} else if (account.institutionState() == InstitutionState.REJECTED) {
			studioApplicationService.rejectApplication(
					applicationId, controlAdmin.userId(), STUDIO_REJECTION_REASON);
		}
	}

	private UUID resumedApplicationId(Account account, User existing) {
		if (account.institutionState() != InstitutionState.PENDING) return null;
		return switch (account.role()) {
			case VENUE -> venueApplicationService.getPendingApplicationByUser(existing.getId()).id();
			case STUDIO -> studioApplicationService.getPendingApplicationByUser(existing.getId()).id();
			default -> null;
		};
	}

	private User resolveSingleExistingIdentity(
			Account account,
			Optional<User> byUsername,
			Optional<User> byEmail
	) {
		if (byUsername.isEmpty() || byEmail.isEmpty()
				|| byUsername.get().getId() == null
				|| !byUsername.get().getId().equals(byEmail.get().getId())) {
			throw lifecycleConflict(account);
		}
		return byUsername.get();
	}

	private void assertCompletedLifecycle(Account account, User user, String commonPassword) {
		UserStatus expectedStatus = finalStatus(account);
		boolean expectedVerified = account.emailVerification() == EmailVerificationState.VERIFIED;
		String expectedRole = mappedRole(account.role()).name();
		boolean roleExpected = !isInstitution(account.role())
				|| expectedStatus == UserStatus.ACTIVE;
		Set<?> roles = user.getRoles() == null ? Set.of() : user.getRoles();
		boolean exactRoles = roleExpected
				? roles.size() == 1 && user.getRoles().stream()
						.anyMatch(role -> expectedRole.equals(role.getName()))
				: roles.isEmpty();

		boolean exactIdentity = account.username().equals(user.getUsername())
				&& account.email().equals(user.getEmail())
				&& user.getProvider() == AuthProvider.LOCAL
				&& user.getErasedAt() == null
				&& user.getStatus() == expectedStatus
				&& Boolean.valueOf(expectedVerified).equals(user.getEmailVerified())
				&& exactRoles
				&& user.getPassword() != null
				&& passwordEncoder.matches(commonPassword, user.getPassword());
		if (!exactIdentity) throw lifecycleConflict(account);
	}

	private void assertSupportedLifecycle(Account account) {
		if (account.role() == null || account.emailVerification() == null) {
			throw new IllegalArgumentException("Simulation account lifecycle is incomplete: " + account.key());
		}
		if (account.emailVerification() == EmailVerificationState.UNVERIFIED
				&& account.role() != AccountRole.MUSICIAN) {
			throw new IllegalArgumentException("Only a musician may remain unverified in simulation");
		}
		if (isInstitution(account.role()) && account.institutionState() == null) {
			throw new IllegalArgumentException("Institution lifecycle state is required: " + account.key());
		}
		if (!isInstitution(account.role()) && account.institutionState() != null) {
			throw new IllegalArgumentException("Personal account cannot have institution state: " + account.key());
		}
		if (account.role() == AccountRole.VENUE
				&& account.institutionState() == InstitutionState.REJECTED) {
			throw new IllegalArgumentException("Rejected venues are not supported by the simulation contract");
		}
	}

	private void assertDecisionAdminAvailable(Account account, SimulationControlAdmin controlAdmin) {
		if (isInstitution(account.role())
				&& account.institutionState() != InstitutionState.PENDING
				&& (controlAdmin == null || controlAdmin.userId() == null)) {
			throw new IllegalArgumentException(
					"Simulation control admin is required for institution decisions");
		}
	}

	private UserStatus finalStatus(Account account) {
		if (account.emailVerification() == EmailVerificationState.UNVERIFIED) {
			return UserStatus.INACTIVE;
		}
		return switch (account.role()) {
			case MUSICIAN, LISTENER -> UserStatus.ACTIVE;
			case VENUE -> account.institutionState() == InstitutionState.APPROVED
					? UserStatus.ACTIVE : UserStatus.PENDING_VENUE_REQUEST;
			case STUDIO -> switch (account.institutionState()) {
				case APPROVED -> UserStatus.ACTIVE;
				case PENDING -> UserStatus.PENDING_STUDIO_REQUEST;
				case REJECTED -> UserStatus.REJECTED_STUDIO_REQUEST;
			};
		};
	}

	private UserStatus initialStatus(AccountRole role) {
		return switch (role) {
			case MUSICIAN, LISTENER -> UserStatus.INACTIVE;
			case VENUE -> UserStatus.PENDING_VENUE_REQUEST;
			case STUDIO -> UserStatus.PENDING_STUDIO_REQUEST;
		};
	}

	private RoleEnum mappedRole(AccountRole role) {
		return switch (role) {
			case MUSICIAN -> RoleEnum.ROLE_MUSICIAN;
			case LISTENER -> RoleEnum.ROLE_LISTENER;
			case VENUE -> RoleEnum.ROLE_VENUE;
			case STUDIO -> RoleEnum.ROLE_STUDIO;
		};
	}

	private boolean isInstitution(AccountRole role) {
		return role == AccountRole.VENUE || role == AccountRole.STUDIO;
	}

	private String requiredAddress(SimulationResolvedLocation location, Account account) {
		if (location.addressLine() == null || location.addressLine().isBlank()) {
			throw new IllegalArgumentException(
					"Institution address is required for simulation account: " + account.key());
		}
		return location.addressLine();
	}

	private String requiredId(UUID id, String level, String accountKey) {
		if (id == null) {
			throw new IllegalArgumentException(
					"Resolved " + level + " id is required for simulation account: " + accountKey);
		}
		return id.toString();
	}

	private <T> void validate(T dto, String operation, String accountKey) {
		Set<ConstraintViolation<T>> violations = validator.validate(dto);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(
					"Invalid simulation " + operation + " DTO for account " + accountKey,
					violations);
		}
	}

	private void requireSuccessfulResponse(
			Account account,
			String operation,
			int expectedCode,
			BaseResponse<?> response
	) {
		if (response == null
				|| !Boolean.TRUE.equals(response.getSuccess())
				|| !Integer.valueOf(expectedCode).equals(response.getCode())) {
			throw new IllegalStateException(
					"Simulation " + operation + " failed for account: " + account.key());
		}
	}

	private IllegalStateException lifecycleConflict(Account account) {
		return new IllegalStateException(
				"Existing simulation account does not match completed manifest lifecycle; "
						+ "run guarded FRESH mode to rebuild: " + account.key());
	}

	private SimulationRegisteredAccount result(
			Account account,
			User user,
			UUID applicationId,
			boolean created
	) {
		return new SimulationRegisteredAccount(
				account.key(), user.getId(), account.role(), user.getUsername(), user.getEmail(),
				user.getStatus(), Boolean.TRUE.equals(user.getEmailVerified()), applicationId, created);
	}
}
