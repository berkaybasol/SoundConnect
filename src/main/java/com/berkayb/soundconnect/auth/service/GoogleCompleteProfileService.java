package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleCompleteProfileService {

	/**
	 * Google self-service onboarding icin izin verilen roller. Admin/owner
	 * rolleri ve ek basvuru/onay akisi gerektiren roller burada yer almaz.
	 */
	private static final Set<RoleEnum> GOOGLE_ONBOARDING_ALLOWED_ROLES = Set.of(
			RoleEnum.ROLE_MUSICIAN,
			RoleEnum.ROLE_LISTENER,
			RoleEnum.ROLE_ORGANIZER,
			RoleEnum.ROLE_PRODUCER
	);

	private final RoleRepository roleRepository;
	private final ProfileFactory profileFactory;
	private final UserRepository userRepository;
	private final JwtTokenProvider jwtTokenProvider;

	/**
	 * Yalniz Google tarafindan dogrulanmis, aktif ve henuz rol atanmamis
	 * kullanicinin profil rolunu bir kez secmesine izin verir.
	 */
	@Transactional
	public LoginResponse completeProfileWithRole(UUID userId, GoogleCompleteProfileRequestDto dto) {
		User user = userRepository.findByIdForUpdate(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		validateGoogleOnboardingAccount(user);

		RoleEnum selectedRoleEnum = dto.role();
		if (!GOOGLE_ONBOARDING_ALLOWED_ROLES.contains(selectedRoleEnum)) {
			throw new SoundConnectException(
					ErrorType.FORBIDDEN_ACCESS,
					List.of("Bu rol Google profil tamamlama akisi ile atanamaz.")
			);
		}

		Role selectedRole = roleRepository.findByName(selectedRoleEnum.name())
				.orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));

		user.setRoles(new HashSet<>(Set.of(selectedRole)));
		userRepository.save(user);
		profileFactory.createProfileIfNeeded(user, selectedRoleEnum);

		log.info("Google onboarding completed. userId={}, role={}", userId, selectedRoleEnum);
		String token = jwtTokenProvider.generateToken(UserDetailsImpl.fromUser(user));
		return LoginResponse.fromUser(token, user);
	}

	private void validateGoogleOnboardingAccount(User user) {
		if (user.getProvider() != AuthProvider.GOOGLE) {
			throw new SoundConnectException(
					ErrorType.FORBIDDEN_ACCESS,
					List.of("Profil tamamlama yalniz Google hesaplari icindir.")
			);
		}
		if (!Boolean.TRUE.equals(user.getEmailVerified()) || user.getStatus() != UserStatus.ACTIVE) {
			throw new SoundConnectException(
					ErrorType.FORBIDDEN_ACCESS,
					List.of("Google hesabi aktif degil.")
			);
		}
		if (user.getRoles() != null && !user.getRoles().isEmpty()) {
			throw new SoundConnectException(ErrorType.USER_ALREADY_REGISTERED);
		}
	}
}
