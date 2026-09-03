package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileProvisioner;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UsernameChangeRequestDto;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.mapper.UserMapper;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.user.support.UserIdentityConflictMapper;
import com.berkayb.soundconnect.modules.user.support.UsernameChangeTimeProvider;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

	private static final int USERNAME_CHANGE_COOLDOWN_DAYS = 30;
	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final UserMapper userMapper;
	private final UserEntityFinder userEntityFinder;
	private final PasswordEncoder passwordEncoder;
	private final UsernameChangeTimeProvider usernameChangeTimeProvider;
	private final PersonalProfileTypePolicy personalProfileTypePolicy;
	private final ListenerProfileProvisioner listenerProfileProvisioner;
	
	// kullaniciyi guncellerken yalnizca dolu gelen alanlari degistiriyoruz
	@Override
	@Transactional
	public Boolean updateUser(UUID actingUserId, UUID id, UserUpdateRequestDto dto) {
		LockedUsers lockedUsers = lockActorAndTarget(actingUserId, id);
		User actor = lockedUsers.actor();
		assertCanManageUsers(actor);
		User user = lockedUsers.target();
		assertCanMutateTarget(actor, user);
		
		boolean isUpdated = false;
		
		// kullanici adi guncellenirse flag true yapilir
		if (dto.username() != null) {
			String normalizedUsername = UsernameUtils.normalizeAndValidate(dto.username());
			if (userRepository.existsByUsernameAndIdNot(normalizedUsername, user.getId())) {
				throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
			}
			user.setUsername(normalizedUsername);
			isUpdated = true;
		}
		
		// eposta guncellenirse flag true
		if (dto.email() != null) {
			String normalizedEmail = EmailUtils.normalize(dto.email());
			if (userRepository.existsByEmailAndIdNot(normalizedEmail, user.getId())) {
				throw new SoundConnectException(ErrorType.EMAIL_ALREADY_EXISTS);
			}
			user.setEmail(normalizedEmail);
			isUpdated = true;
		}
		
		// sifre guncellenirse hashlenerek set edilir
		if (dto.password() != null) {
			user.setPassword(passwordEncoder.encode(dto.password()));
			isUpdated = true;
		}
		
		// ROLE GÜNCELLEME: önce null check
		if (dto.roleId() != null) {
			Role role = roleRepository.findById(dto.roleId())
			                          .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
			assertCanAssignRole(actor, user, role);
			personalProfileTypePolicy.assertRoleReplacementAllowed(user, role);
			user.setRoles(new HashSet<>(Set.of(role)));
			isUpdated = true;
		}
		
		
		// herhangi bir alan guncellenmisse updatedAt set edilir ve kaydedilir
		if (isUpdated) {
			user.setUpdatedAt(LocalDateTime.now());
			User saved = saveIdentityAndFlush(user);
			ensureListenerProfileInvariant(saved);
			log.info("User updated by administrator. actorId={} targetId={} roleChanged={}",
					actingUserId, id, dto.roleId() != null);
		}
		
		return isUpdated;
	}

	@Override
	@Transactional
	public String changeUsername(UUID userId, UsernameChangeRequestDto dto) {
		User user = findForUpdate(userId);
		String normalizedUsername = UsernameUtils.normalizeAndValidate(dto.username());
		String currentCanonicalUsername = UsernameUtils.normalize(user.getUsername());

		if (normalizedUsername.equals(currentCanonicalUsername)) {
			if (!normalizedUsername.equals(user.getUsername())) {
				user.setUsername(normalizedUsername);
				saveIdentityAndFlush(user);
			}
			return normalizedUsername;
		}

		LocalDateTime now = usernameChangeTimeProvider.now();
		LocalDateTime lastChangedAt = user.getUsernameChangedAt();
		if (lastChangedAt != null
				&& now.isBefore(lastChangedAt.plusDays(USERNAME_CHANGE_COOLDOWN_DAYS))) {
			throw new SoundConnectException(ErrorType.USERNAME_CHANGE_COOLDOWN_ACTIVE);
		}

		if (userRepository.existsByUsernameAndIdNot(normalizedUsername, userId)) {
			throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
		}

		user.setUsername(normalizedUsername);
		user.setUsernameChangedAt(now);
		return saveIdentityAndFlush(user).getUsername();
	}
	
	// kullaniciyi id'ye gore siler
	@Override
	@Transactional
	public void deleteUserById(UUID actingUserId, UUID id) {
		LockedUsers lockedUsers = lockActorAndTarget(actingUserId, id);
		User actor = lockedUsers.actor();
		assertCanManageUsers(actor);
		if (actingUserId.equals(id)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		User user = lockedUsers.target();
		assertCanMutateTarget(actor, user);
		assertLastOwnerIsPreserved(user, null);
		userRepository.delete(user);
		log.info("User deleted by administrator. actorId={} targetId={}", actingUserId, id);
	}
	
	// id'ye gore kullaniciyi getirir
	@Override
	@Transactional(readOnly = true)
	public UserListDto getUserById(UUID id) {
		User user = userEntityFinder.getUser(id);
		return userMapper.toDto(user);
	}
	
	// tum kullanicilari listeler
	@Transactional(readOnly = true)
	@Override
	public List<UserListDto> getAllUsers() {
		return userRepository.findAll().stream()
		                     .map(userMapper::toDto)
		                     .toList();
	}
	
	// yeni kullanici kaydeder, varsa enstrumanlarini da ekler
	@Override
	@Transactional
	public User saveUser(UUID actingUserId, UserSaveRequestDto dto) {
		User actor = findForUpdate(actingUserId);
		assertCanManageUsers(actor);
		String normalizedEmail = EmailUtils.normalize(dto.email());
		String normalizedUsername = UsernameUtils.normalizeAndValidate(dto.username());
		if (userRepository.existsByUsername(normalizedUsername)) {
			throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
		}
		if (userRepository.existsByEmail(normalizedEmail)) {
			throw new SoundConnectException(ErrorType.EMAIL_ALREADY_EXISTS);
		}
		// role
		Role role = roleRepository.findById(dto.roleId())
		                          .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		assertCanAssignRole(actor, null, role);
		
		
		// elle user oluştur
		User user = User.builder()
		                .username(normalizedUsername)
		                .email(normalizedEmail)
		                .password(passwordEncoder.encode(dto.password()))
		                .roles(Set.of(role))
		                .status(UserStatus.ACTIVE)
		                .emailVerified(true)
		                .createdAt(LocalDateTime.now())
		                .build();

		User saved = saveIdentityAndFlush(user);
		ensureListenerProfileInvariant(saved);
		log.info("User created by administrator. actorId={} targetId={} role={}",
				actingUserId, saved.getId(), role.getName());
		return saved;
	}

	private void ensureListenerProfileInvariant(User user) {
		if (user.getRoles() == null || user.getRoles().stream()
				.noneMatch(role -> RoleEnum.ROLE_LISTENER.name().equals(role.getName()))) {
			return;
		}
		listenerProfileProvisioner.ensureExistsForUpdate(user.getId());
	}

	private User saveIdentityAndFlush(User user) {
		try {
			return userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException exception) {
			throw UserIdentityConflictMapper.map(exception);
		}
	}

	private User findForUpdate(UUID userId) {
		return userRepository.findByIdForUpdate(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
	}

	private LockedUsers lockActorAndTarget(UUID actorId, UUID targetId) {
		if (actorId.equals(targetId)) {
			User user = findForUpdate(actorId);
			return new LockedUsers(user, user);
		}

		UUID firstId = actorId.compareTo(targetId) <= 0 ? actorId : targetId;
		UUID secondId = firstId.equals(actorId) ? targetId : actorId;
		User first = findForUpdate(firstId);
		User second = findForUpdate(secondId);
		return firstId.equals(actorId)
				? new LockedUsers(first, second)
				: new LockedUsers(second, first);
	}

	private void assertCanManageUsers(User actor) {
		if (!hasRole(actor, "ROLE_OWNER") && !hasPermission(actor, "MANAGE_USERS")) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private void assertCanMutateTarget(User actor, User target) {
		if (hasRole(target, "ROLE_OWNER") && !hasRole(actor, "ROLE_OWNER")) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private void assertCanAssignRole(User actor, User currentTarget, Role newRole) {
		if (!hasRole(actor, "ROLE_OWNER") && !hasPermission(actor, "MANAGE_ROLES")) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		if ("ROLE_OWNER".equals(newRole.getName()) && !hasRole(actor, "ROLE_OWNER")) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		assertLastOwnerIsPreserved(currentTarget, newRole);
	}

	private void assertLastOwnerIsPreserved(User currentTarget, Role replacementRole) {
		if (currentTarget == null || !hasRole(currentTarget, "ROLE_OWNER")) {
			return;
		}
		boolean remainsOwner = replacementRole != null && "ROLE_OWNER".equals(replacementRole.getName());
		if (!remainsOwner) {
			// ROLE_OWNER is a stable, shared database mutex for every destructive
			// owner transition. Acquiring it before counting serializes concurrent
			// downgrades/deletions so two transactions cannot both observe "2".
			roleRepository.findByNameForUpdate("ROLE_OWNER")
			              .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
			if (userRepository.countDistinctByRoles_Name("ROLE_OWNER") <= 1) {
				throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
			}
		}
	}

	private boolean hasRole(User user, String roleName) {
		return user.getRoles() != null && user.getRoles().stream()
				.anyMatch(role -> roleName.equals(role.getName()));
	}

	private boolean hasPermission(User user, String permissionName) {
		boolean direct = user.getPermissions() != null && user.getPermissions().stream()
				.map(Permission::getName)
				.anyMatch(permissionName::equals);
		if (direct) return true;
		return user.getRoles() != null && user.getRoles().stream()
				.filter(role -> role.getPermissions() != null)
				.flatMap(role -> role.getPermissions().stream())
				.map(Permission::getName)
				.anyMatch(permissionName::equals);
	}

	private record LockedUsers(User actor, User target) {
	}
}
