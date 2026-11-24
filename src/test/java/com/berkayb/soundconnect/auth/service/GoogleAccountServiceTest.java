package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
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
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleAccountServiceTest {
	private static final VerifiedGoogleIdentity ID = new VerifiedGoogleIdentity("subject", "user@gmail.com", "Person", true);
	@Mock UserRepository users;
	@Mock RoleRepository roles;
	@Mock PersonalProfileTypePolicy policy;
	@Mock ProfileFactory profiles;
	@Mock PasswordEncoder passwords;
	@Mock JwtTokenProvider tokens;
	@Mock ListenerProfileChoiceStatusReader choices;
	@Mock GoogleBusinessApplications applications;
	@InjectMocks GoogleAccountService service;

	@Test void accountChoiceOrCancellationCannotPersistANewUserOrStartASession() {
		var result = service.signIn(ID);
		assertThat(result.requiresGoogleProfileCompletion()).isTrue();
		assertThat(result.userId()).isNull();
		assertThat(result.token()).isNull();
		verify(users, never()).saveAndFlush(any());
		verifyNoInteractions(tokens, profiles, passwords);
	}

	@Test void suspendedGoogleAccountCannotResumeOnboardingOrReceiveAnySession() {
		User user = account();
		user.setAccessSuspended(true);
		when(users.findByProviderSubjectForUpdate(AuthProvider.GOOGLE, ID.subject())).thenReturn(Optional.of(user));
		expectError(() -> service.signIn(ID), ErrorType.ACCOUNT_SUSPENDED);
		expectError(() -> service.complete(ID, form(RoleEnum.ROLE_MUSICIAN)), ErrorType.ACCOUNT_SUSPENDED);
		verifyNoInteractions(applications, tokens, profiles);
	}

	@Test void localMailboxAndConflictingBoundSubjectsAreNeverLinked() {
		User local = account();
		local.setProvider(AuthProvider.LOCAL);
		when(users.findByEmailForUpdate(ID.email())).thenReturn(Optional.of(local));
		expectError(() -> service.signIn(ID), ErrorType.GOOGLE_LOCAL_ACCOUNT_USE_PASSWORD);
		local.setProvider(AuthProvider.GOOGLE);
		local.setProviderSubject("another-subject");
		expectError(() -> service.signIn(ID), ErrorType.UNAUTHORIZED);
		verifyNoInteractions(tokens, profiles);
	}

	@Test void thirdPartyMailboxCannotBindAnExistingSubjectlessAccountOrCreateANewVerifiedMailbox() {
		var identity = new VerifiedGoogleIdentity("sub", "external@example.invalid", null, false);
		expectError(() -> service.signIn(identity), ErrorType.GOOGLE_EMAIL_OWNERSHIP_REQUIRED);
		expectError(() -> service.complete(identity, form(RoleEnum.ROLE_MUSICIAN)), ErrorType.GOOGLE_EMAIL_OWNERSHIP_REQUIRED);
		verify(users, never()).findByEmailForUpdate(any());
		verifyNoInteractions(tokens, profiles);
	}

	@Test void alreadyBoundSubjectStillSignsInAfterEmailChangesWithoutReassigningEitherMailbox() {
		User user = account(RoleEnum.ROLE_ORGANIZER);
		var identity = new VerifiedGoogleIdentity(ID.subject(), "external@example.invalid", null, false);
		when(users.findByProviderSubjectForUpdate(AuthProvider.GOOGLE, identity.subject())).thenReturn(Optional.of(user));
		when(tokens.generateToken(any())).thenReturn("session");
		assertThat(service.signIn(identity).roles()).containsExactly("ROLE_ORGANIZER");
		assertThat(user.getEmail()).isEqualTo(ID.email());
		verify(users, never()).findByEmailForUpdate(any());
	}

	@ParameterizedTest @EnumSource(value = UserStatus.class, names = {"PENDING_VENUE_REQUEST", "PENDING_STUDIO_REQUEST", "REJECTED_STUDIO_REQUEST"})
	void pendingAndRejectedApplicantsNeverReceiveANormalGoogleSession(UserStatus status) {
		User user = account();
		user.setStatus(status);
		when(users.findByProviderSubjectForUpdate(AuthProvider.GOOGLE, ID.subject())).thenReturn(Optional.of(user));
		service.signIn(ID);
		verify(applications).resume(user);
		verifyNoInteractions(tokens);
	}

	@ParameterizedTest @EnumSource(value = RoleEnum.class, names = {"ROLE_MUSICIAN", "ROLE_LISTENER"})
	void explicitConfirmationCreatesOnlySelectedProfileAndReturnsTheOrdinaryRoleSession(RoleEnum role) {
		when(roles.findByName(role.name())).thenReturn(Optional.of(Role.builder().name(role.name()).build()));
		when(passwords.encode(any())).thenReturn("opaque-hash");
		when(users.saveAndFlush(any())).thenAnswer(call -> {
			User user = call.getArgument(0);
			user.setId(UUID.randomUUID());
			assertThat(user.getUsername()).isEqualTo("newname");
			assertThat(user.getEmail()).isEqualTo(ID.email());
			assertThat(user.getProviderSubject()).isEqualTo(ID.subject());
			assertThat(user.getPassword()).isEqualTo("opaque-hash");
			return user;
		});
		when(tokens.generateToken(any())).thenReturn("session");
		when(choices.requiresChoice(any())).thenReturn(role == RoleEnum.ROLE_LISTENER);
		var result = service.complete(ID, form(role));
		assertThat(result.requiresGoogleProfileCompletion()).isFalse();
		assertThat(result.token()).isEqualTo("session");
		assertThat(result.roles()).containsExactly(role.name());
		assertThat(result.requiresListenerProfileChoice()).isEqualTo(role == RoleEnum.ROLE_LISTENER);
		verify(profiles).createProfileIfNeeded(any(), eq(role));
	}

	@Test void legacyPlaceholderRetainsItsIdAndRevokesPriorBearersAtomically() {
		User user = account(RoleEnum.ROLE_USER);
		when(users.findByEmailForUpdate(ID.email())).thenReturn(Optional.of(user));
		var before = service.signIn(ID);
		assertThat(before.requiresGoogleProfileCompletion()).isTrue();
		assertThat(before.token()).isNull();
		when(roles.findByName("ROLE_LISTENER")).thenReturn(Optional.of(Role.builder().name("ROLE_LISTENER").build()));
		when(users.revokeGoogleOnboardingSessions(user.getId(), 0)).thenReturn(1);
		when(users.saveAndFlush(user)).thenReturn(user);
		var result = service.complete(ID, form(RoleEnum.ROLE_LISTENER));
		assertThat(result.userId()).isEqualTo(user.getId());
		assertThat(user.getProviderSubject()).isEqualTo(ID.subject());
		assertThat(user.getSessionVersion()).isOne();
		assertThat(user.getUsername()).isEqualTo("newname");
		verify(policy).assertCanAcquire(user, RoleEnum.ROLE_LISTENER);
		verify(profiles).createProfileIfNeeded(user, RoleEnum.ROLE_LISTENER);
	}

	@Test void existingProfileEvidencePreventsChangingToAnotherPersonalType() {
		User user = account();
		when(users.findByEmailForUpdate(ID.email())).thenReturn(Optional.of(user));
		doThrow(new SoundConnectException(ErrorType.PROFILE_TYPE_IMMUTABLE)).when(policy).assertCanAcquire(user, RoleEnum.ROLE_MUSICIAN);
		expectError(() -> service.complete(ID, form(RoleEnum.ROLE_MUSICIAN)), ErrorType.PROFILE_TYPE_IMMUTABLE);
		verifyNoInteractions(profiles, tokens);
	}

	@Test void confirmedRetryIsIdempotentAndAnotherSelectionCannotOverwriteACompletedAccount() {
		User user = account(RoleEnum.ROLE_MUSICIAN);
		user.setUsername("newname");
		when(users.findByProviderSubjectForUpdate(AuthProvider.GOOGLE, ID.subject())).thenReturn(Optional.of(user));
		when(tokens.generateToken(any())).thenReturn("session");
		assertThat(service.complete(ID, form(RoleEnum.ROLE_MUSICIAN)).token()).isEqualTo("session");
		expectError(() -> service.complete(ID, form(RoleEnum.ROLE_LISTENER)), ErrorType.USER_ALREADY_REGISTERED);
		verifyNoInteractions(profiles, roles);
		verify(users, never()).saveAndFlush(any());
	}

	@ParameterizedTest @EnumSource(value = RoleEnum.class, names = {"ROLE_VENUE","ROLE_STUDIO"})
	void confirmedBusinessKeepsTheSubjectButGrantsNoProfileBeforeApproval(RoleEnum role) {
		var saved = new java.util.concurrent.atomic.AtomicReference<User>();
		when(passwords.encode(any())).thenReturn("opaque-hash");
		when(users.saveAndFlush(any())).thenAnswer(call -> {
			User applicant = call.getArgument(0);
			applicant.setId(UUID.randomUUID());
			saved.set(applicant);
			return applicant;
		});
		var request = form(role);
		service.complete(ID, request);
		User applicant = saved.get();
		assertThat(applicant.getProviderSubject()).isEqualTo(ID.subject());
		assertThat(applicant.getUsername()).isEqualTo("newname");
		assertThat(applicant.getStatus()).isEqualTo(role == RoleEnum.ROLE_VENUE
				? UserStatus.PENDING_VENUE_REQUEST : UserStatus.PENDING_STUDIO_REQUEST);
		assertThat(applicant.getEmailVerified()).isTrue();
		assertThat(applicant.getRoles()).isEmpty();
		assertThat(applicant.getPermissions()).isEmpty();
		when(users.findByProviderSubjectForUpdate(AuthProvider.GOOGLE, ID.subject())).thenReturn(Optional.of(applicant));
		service.complete(ID, request);
		service.signIn(ID);
		verify(applications).submit(applicant, request);
		verify(applications, times(2)).resume(applicant);
		expectError(() -> service.complete(ID, form(RoleEnum.ROLE_LISTENER)), ErrorType.USER_ALREADY_REGISTERED);
		expectError(() -> service.complete(ID, new GoogleCompleteProfileRequestDto("proof","different", role, true)),
				ErrorType.USER_ALREADY_REGISTERED);
		verify(users, times(1)).saveAndFlush(any());
		verifyNoInteractions(tokens, profiles, roles);
	}

	@Test void noConsentOrUnavailableUsernameLeavesNoAccount() {
		expectError(() -> service.complete(ID, new GoogleCompleteProfileRequestDto("proof", "name", RoleEnum.ROLE_LISTENER, false)), ErrorType.VALIDATION_ERROR);
		when(users.existsByUsername("newname")).thenReturn(true);
		expectError(() -> service.complete(ID, form(RoleEnum.ROLE_LISTENER)), ErrorType.USER_ALREADY_EXISTS);
		verify(users, never()).saveAndFlush(any());
		verifyNoInteractions(tokens, profiles);
	}

	@ParameterizedTest @EnumSource(value = RoleEnum.class, mode = EnumSource.Mode.EXCLUDE,
			names = {"ROLE_LISTENER","ROLE_MUSICIAN","ROLE_VENUE","ROLE_STUDIO"})
	void otherProfilesAndPrivilegedRolesCannotBeAcquired(RoleEnum role) {
		expectError(() -> service.complete(ID, form(role)), ErrorType.FORBIDDEN_ACCESS);
		verifyNoInteractions(users, profiles, tokens);
	}

	private static GoogleCompleteProfileRequestDto form(RoleEnum role) {
		return new GoogleCompleteProfileRequestDto("proof", " NewName ", role, true);
	}

	private static User account(RoleEnum... role) {
		var user = User.builder().id(UUID.randomUUID()).email(ID.email()).username("legacy")
				.provider(AuthProvider.GOOGLE).status(UserStatus.ACTIVE).emailVerified(true).build();
		for (RoleEnum r : role) user.getRoles().add(Role.builder().name(r.name()).build());
		return user;
	}

	private static void expectError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorType error) {
		assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
				failure -> assertThat(failure.getErrorType()).isEqualTo(error));
	}
}
