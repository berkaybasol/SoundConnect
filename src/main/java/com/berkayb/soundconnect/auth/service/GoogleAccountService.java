package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserIdentityConflictMapper;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoogleAccountService {
	private static final Set<RoleEnum> SELF_REGISTRATION = Set.of(
			RoleEnum.ROLE_LISTENER, RoleEnum.ROLE_MUSICIAN, RoleEnum.ROLE_VENUE, RoleEnum.ROLE_STUDIO);

	private final UserRepository users;
	private final RoleRepository roles;
	private final PersonalProfileTypePolicy profileTypes;
	private final ProfileFactory profiles;
	private final PasswordEncoder passwords;
	private final JwtTokenProvider tokens;
	private final ListenerProfileChoiceStatusReader listenerChoice;
	private final GoogleBusinessApplications applications;

	@Transactional
	public LoginResponse signIn(VerifiedGoogleIdentity identity) {
		User user = existingAccount(identity);
		if (user == null) return LoginResponse.googleProfileRequired();
		if (GoogleBusinessApplications.applicantRole(user.getStatus()) != null) {
			bindSubject(user, identity);
			return applications.resume(user);
		}
		if (user.isGoogleProfileIncomplete()) {
			return LoginResponse.googleProfileRequired();
		}
		// Bind an existing, verified legacy Google account only after checking provider and identity.
		return session(user, identity);
	}

	@Transactional
	public LoginResponse complete(VerifiedGoogleIdentity identity, GoogleCompleteProfileRequestDto request) {
		if (!request.consent()) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		if (request.role() == null || !SELF_REGISTRATION.contains(request.role())) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		String username = UsernameUtils.normalizeAndValidate(request.username());
		users.lockGoogleOnboarding(identity.subject());
		User user = existingAccount(identity);
		RoleEnum pendingRole = user == null ? null : GoogleBusinessApplications.applicantRole(user.getStatus());
		if (pendingRole != null) {
			if (pendingRole != request.role() || !username.equals(user.getUsername())) {
				throw new SoundConnectException(ErrorType.USER_ALREADY_REGISTERED);
			}
			bindSubject(user, identity);
			return applications.resume(user);
		}
		if (user != null && !user.isGoogleProfileIncomplete()) {
			// A retry after a committed response must not change the profile or its permissions.
			if (username.equals(user.getUsername()) && user.getRoles().size() == 1
					&& user.getRoles().iterator().next().getName().equals(request.role().name())) {
				return session(user, identity);
			}
			throw new SoundConnectException(ErrorType.USER_ALREADY_REGISTERED);
		}

		UserStatus targetStatus = switch (request.role()) {
			case ROLE_VENUE -> UserStatus.PENDING_VENUE_REQUEST;
			case ROLE_STUDIO -> UserStatus.PENDING_STUDIO_REQUEST;
			default -> UserStatus.ACTIVE;
		};
		boolean profileExists = false;
		if (user == null) {
			if (users.existsByUsername(username)) {
				throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
			}
			user = User.builder().username(username).email(identity.email()).providerSubject(identity.subject())
					.provider(AuthProvider.GOOGLE).emailVerified(true)
					.password(passwords.encode(UUID.randomUUID().toString())).build();
		} else {
			if (users.existsByUsernameAndIdNot(username, user.getId())) {
				throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
			}
			// An incomplete account has no personal role. Any type evidence here
			// comes from a stored profile, which must keep its ID and existing content.
			profileExists = profileTypes.assertCanAcquire(user, request.role());
			long previous = user.getSessionVersion();
			if (users.revokeGoogleOnboardingSessions(user.getId(), previous) != 1) {
				throw new IllegalStateException("Google onboarding session revision changed under lock");
			}
			user.setSessionVersion(Math.incrementExact(previous));
			user.setUsername(username);
		}
		bindSubject(user, identity);
		user.setStatus(targetStatus);
		if (targetStatus == UserStatus.ACTIVE) {
			Role role = roles.findByName(request.role().name())
					.orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
			user.setRoles(new HashSet<>(Set.of(role)));
		} else {
			// Approval assigns the institutional role and provisions the actual venue or studio.
			user.setRoles(new HashSet<>());
		}
		try {
			user = users.saveAndFlush(user);
		} catch (DataIntegrityViolationException conflict) {
			throw UserIdentityConflictMapper.map(conflict);
		}
		if (targetStatus != UserStatus.ACTIVE) return applications.submit(user, request);
		if (!profileExists) {
			profiles.createProfileIfNeeded(user, request.role());
		}
		return session(user, identity);
	}

	private User existingAccount(VerifiedGoogleIdentity identity) {
		User user = users.findByProviderSubjectForUpdate(AuthProvider.GOOGLE, identity.subject()).orElse(null);
		if (user == null) {
			// A verified third-party mailbox can have changed hands since Google verified it.
			if (!identity.authoritativeEmail()) {
				throw new SoundConnectException(ErrorType.GOOGLE_EMAIL_OWNERSHIP_REQUIRED);
			}
			user = users.findByEmailForUpdate(identity.email()).orElse(null);
		}
		if (user == null) return null;
		if (user.getProvider() != AuthProvider.GOOGLE) {
			throw new SoundConnectException(ErrorType.GOOGLE_LOCAL_ACCOUNT_USE_PASSWORD);
		}
		String subject = user.getProviderSubject();
		if (subject != null && !subject.isBlank() && !subject.equals(identity.subject())) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		if (user.getErasedAt() != null || !Boolean.TRUE.equals(user.getEmailVerified())
				|| (user.getStatus() != UserStatus.ACTIVE && GoogleBusinessApplications.applicantRole(user.getStatus()) == null)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		return user;
	}

	private static void bindSubject(User user, VerifiedGoogleIdentity identity) {
		if (user.getProviderSubject() == null || user.getProviderSubject().isBlank()) {
			user.setProviderSubject(identity.subject());
		}
	}

	private LoginResponse session(User user, VerifiedGoogleIdentity identity) {
		bindSubject(user, identity);
		String token = tokens.generateToken(UserDetailsImpl.fromUser(user));
		return LoginResponse.fromUser(token, user, listenerChoice.requiresChoice(user));
	}
}
