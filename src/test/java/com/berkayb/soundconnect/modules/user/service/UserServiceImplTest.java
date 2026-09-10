package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.modules.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileProvisioner;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UsernameChangeRequestDto;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.mapper.UserMapper;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.user.support.UsernameChangeTimeProvider;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl için UNIT TEST
 *
 * Bu sınıfın amacı:
 * 1) getAllUsers:
 *    - Repository'den dönen User entity'lerinin Mapper ile DTO'ya çevrildiğini,
 *    - Dönen listenin beklenen boyut ve sırada olduğunu doğrulamak.
 *
 * 2) updateUser:
 *    - Her alan (username, email, password, roleId) güncellendiğinde save + updatedAt set edildiğini,
 *    - roleId bulunamayınca hata fırlatıldığını,
 *    - Hiç alan gelmeyince save çağrılmadığını ve false döndüğünü doğrulamak.
 */
@Tag("service")
class UserServiceImplTest {

	private static final LocalDateTime FIXED_NOW =
			LocalDateTime.of(2026, 7, 24, 12, 0);
	
	// ==== Bağımlılıklar (mock) ====
	@Mock private UserRepository userRepository;     // DB'ye gitmemek için sahte repo
	@Mock private RoleRepository roleRepository;     // Rol değişimi için sahte repo
	@Mock private UserMapper userMapper;             // Entity->DTO dönüşümü sahte
	@Mock private UserEntityFinder userEntityFinder; // update'te id'den user bulma sahte
	@Mock private PasswordEncoder passwordEncoder;   // parola hash'leme sahte
	@Mock private UsernameChangeTimeProvider usernameChangeTimeProvider;
	@Mock private CityRepository cityRepository;     // ctor bağımlılığı; bu testte kullanılmıyor
	@Mock private PersonalProfileTypePolicy personalProfileTypePolicy;
	@Mock private ListenerProfileProvisioner listenerProfileProvisioner;
	@Mock private com.berkayb.soundconnect.modules.user.deletion.ListenerAccountDeletionService listenerAccountDeletionService;
	
	// ==== Test edeceğimiz servis ====
	@InjectMocks
	private UserServiceImpl userService;             // Mock'lar otomatik enjekte edilir
	
	// Kaydedilen User'ı yakalamak için (save çağrılarında argümanı çekeriz)
	@Captor
	private ArgumentCaptor<User> userCaptor;
	
	private UUID userId;     // Test boyunca sabit user id
	private User existingUser; // Finder'ın döndüreceği mevcut kullanıcı
	
	@BeforeEach
	void setUp() {
		// Mockito anotasyonlarını aktif eder (mock, injectmocks, captor)
		MockitoAnnotations.openMocks(this);
		
		// Ortak user (update testleri için başlangıç durumu)
		userId = UUID.randomUUID();
		existingUser = User.builder()
		                   .id(userId)
		                   .username("oldName")
		                   .email("old@mail.com")
		                   .password("old-hash")
		                   .status(UserStatus.ACTIVE)
		                   .roles(Set.of(Role.builder().name("ROLE_OWNER").build()))
		                   .build();
		
		// Finder default davranışı: her çağrıda aynı existingUser dönsün
		when(userEntityFinder.getUser(userId)).thenReturn(existingUser);
		when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(existingUser));
		when(usernameChangeTimeProvider.now()).thenReturn(FIXED_NOW);
		
		// Repository.saveAndFlush(...) çağrıldığında, verilen argümanı aynen geri döndür.
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
	}
	
	// =======================
	// getAllUsers() SENARYOSU
	// =======================
	
	/**
	 * Amaç:
	 * - userRepository.findAll() çağrılır,
	 * - Her entity için userMapper.toDto(...) çalışır,
	 * - Dönen liste beklenen DTO'ları ve sıralamayı içerir.
	 */
	@Test
	void getAllUsers_ShouldReturnMappedDtos() {
		// 1) Sahte entity verisi (DB'ye gitmiyoruz, repo'yu mock'layacağız)
		User u1 = User.builder().id(UUID.randomUUID()).username("Berkay").gender(Gender.MALE).build();
		User u2 = User.builder().id(UUID.randomUUID()).username("Ahmet").gender(Gender.MALE).build();
		
		// 2) Sahte City DTO (UserListDto'nun alanlarını doldurmak için)
		CityResponseDto cityDto = new CityResponseDto(UUID.randomUUID(), "Ankara");
		
		// 3) Beklenen DTO'lar (mapper sonucunu biz belirliyoruz)
		UserListDto d1 = UserListDto.builder()
		                            .id(u1.getId())
		                            .username("Berkay")
		                            .gender(Gender.MALE)
		                            .city(cityDto)
		                            .followers(10)
		                            .following(5)
		                            .emailVerified(true)
		                            .roles(Set.of("ROLE_USER"))
		                            .build();
		
		UserListDto d2 = UserListDto.builder()
		                            .id(u2.getId())
		                            .username("Ahmet")
		                            .gender(Gender.MALE)
		                            .city(cityDto)
		                            .followers(3)
		                            .following(8)
		                            .emailVerified(false)
		                            .roles(Set.of("ROLE_ADMIN"))
		                            .build();
		
		// 4) Mock davranışları:
		// - findAll çağrılınca u1, u2 dönsün
		// - mapper her entity için bizim hazırladığımız DTO'yu dönsün
		when(userRepository.findAll()).thenReturn(List.of(u1, u2));
		when(userMapper.toDto(u1)).thenReturn(d1);
		when(userMapper.toDto(u2)).thenReturn(d2);
		
		// 5) Servis metodunu çağır
		List<UserListDto> result = userService.getAllUsers();
		
		// 6) Doğrulama:
		// - Boyut ve sıra kontrolü
		// - Beklenen etkileşimler yapıldı mı?
		assertThat(result).hasSize(2).containsExactly(d1, d2);
		verify(userRepository).findAll();
		verify(userMapper).toDto(u1);
		verify(userMapper).toDto(u2);
		verifyNoMoreInteractions(userMapper, userRepository); // fazladan istenmeyen çağrı olmasın
	}
	
	// ===========================
	// updateUser() SENARYOLARI
	// ===========================
	
	/**
	 * username alanı gelirse:
	 * - User.username güncellenmeli
	 * - updatedAt set edilmeli
	 * - save çağrılmalı
	 * - method true dönmeli
	 */
	@Test
	void updateUser_WhenUsernameChanged_ShouldUpdateAndSaveAndReturnTrue() {
		// DTO'da sadece username veriyoruz, diğer alanlar null
		UserUpdateRequestDto dto = new UserUpdateRequestDto(" NewName ", null, null, null);
		
		// Çalıştır
		Boolean updated = userService.updateUser(userId, userId, dto);
		
		// Beklenti: true + save çağrıldı + username değişti + updatedAt yazıldı
		assertThat(updated).isTrue();
		verify(userRepository).saveAndFlush(userCaptor.capture());   // kaydedilen User'ı yakala
		
		User saved = userCaptor.getValue();
		assertThat(saved.getUsername()).isEqualTo("newname");
		assertThat(saved.getUpdatedAt()).isNotNull();        // zaman damgası set edilmiş olmalı
		verify(userRepository).existsByUsernameAndIdNot("newname", userId);
	}

	@Test
	void updateUser_WhenCanonicalUsernameBelongsToAnotherUser_ShouldReject() {
		when(userRepository.existsByUsernameAndIdNot("taken", userId)).thenReturn(true);

		assertThatThrownBy(() -> userService.updateUser(
				userId,
				userId,
				new UserUpdateRequestDto(" TaKeN ", null, null, null)
		)).isInstanceOfSatisfying(SoundConnectException.class,
				exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.USER_ALREADY_EXISTS));

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void updateUser_WhenDatabaseDetectsConcurrentUsernameConflict_ShouldMapFriendlyError() {
		when(userRepository.saveAndFlush(existingUser)).thenThrow(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint ux_tbl_user_username_canonical; Key (user_name)"
		));

		assertThatThrownBy(() -> userService.updateUser(
				userId,
				userId,
				new UserUpdateRequestDto(" Contested ", null, null, null)
		)).isInstanceOfSatisfying(SoundConnectException.class,
				exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.USER_ALREADY_EXISTS));
	}

	@Test
	void changeUsername_WithNoPreviousChange_AllowsFirstChangeAndSetsTimestamp() {
		String username = userService.changeUsername(
				userId,
				new UsernameChangeRequestDto(" NewName ")
		);

		assertThat(username).isEqualTo("newname");
		assertThat(existingUser.getUsername()).isEqualTo("newname");
		assertThat(existingUser.getUsernameChangedAt()).isEqualTo(FIXED_NOW);
		verify(userRepository).existsByUsernameAndIdNot("newname", userId);
		verify(userRepository).saveAndFlush(existingUser);
	}

	@Test
	void changeUsername_DuringCooldown_RejectsBeforeAvailabilityLookup() {
		LocalDateTime lastChangedAt = FIXED_NOW.minusDays(30).plusNanos(1);
		existingUser.setUsernameChangedAt(lastChangedAt);

		assertThatThrownBy(() -> userService.changeUsername(
				userId,
				new UsernameChangeRequestDto("newname")
		)).isInstanceOfSatisfying(SoundConnectException.class,
				exception -> assertThat(exception.getErrorType())
						.isEqualTo(ErrorType.USERNAME_CHANGE_COOLDOWN_ACTIVE));

		assertThat(existingUser.getUsername()).isEqualTo("oldName");
		assertThat(existingUser.getUsernameChangedAt()).isEqualTo(lastChangedAt);
		verify(userRepository, never()).existsByUsernameAndIdNot(anyString(), any());
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void changeUsername_AtExactThirtyDayBoundary_AllowsChange() {
		existingUser.setUsernameChangedAt(FIXED_NOW.minusDays(30));

		String username = userService.changeUsername(
				userId,
				new UsernameChangeRequestDto(" BoundaryName ")
		);

		assertThat(username).isEqualTo("boundaryname");
		assertThat(existingUser.getUsernameChangedAt()).isEqualTo(FIXED_NOW);
		verify(userRepository).saveAndFlush(existingUser);
	}

	@Test
	void changeUsername_SameCanonicalValueIsIdempotentAndDoesNotCheckCooldown() {
		existingUser.setUsername("oldname");
		LocalDateTime activeCooldown = FIXED_NOW.minusDays(1);
		existingUser.setUsernameChangedAt(activeCooldown);

		String username = userService.changeUsername(
				userId,
				new UsernameChangeRequestDto(" OLDNAME ")
		);

		assertThat(username).isEqualTo("oldname");
		assertThat(existingUser.getUsernameChangedAt()).isEqualTo(activeCooldown);
		verifyNoInteractions(usernameChangeTimeProvider);
		verify(userRepository, never()).existsByUsernameAndIdNot(anyString(), any());
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void changeUsername_SameLegacyCanonicalValueRepairsStorageWithoutConsumingCooldown() {
		existingUser.setUsername(" OldName ");
		LocalDateTime activeCooldown = FIXED_NOW.minusDays(1);
		existingUser.setUsernameChangedAt(activeCooldown);

		String username = userService.changeUsername(
				userId,
				new UsernameChangeRequestDto(" OLDNAME ")
		);

		assertThat(username).isEqualTo("oldname");
		assertThat(existingUser.getUsername()).isEqualTo("oldname");
		assertThat(existingUser.getUsernameChangedAt()).isEqualTo(activeCooldown);
		verifyNoInteractions(usernameChangeTimeProvider);
		verify(userRepository, never()).existsByUsernameAndIdNot(anyString(), any());
		verify(userRepository).saveAndFlush(existingUser);
	}

	@Test
	void changeUsername_WhenTaken_ShouldRejectBeforePersistence() {
		when(userRepository.existsByUsernameAndIdNot("taken", userId)).thenReturn(true);

		assertThatThrownBy(() -> userService.changeUsername(
				userId,
				new UsernameChangeRequestDto(" TaKeN ")
		)).isInstanceOfSatisfying(SoundConnectException.class,
				exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.USER_ALREADY_EXISTS));

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void changeUsername_WhenDatabaseWinsConcurrentRace_ShouldMapFriendlyConflict() {
		when(userRepository.saveAndFlush(existingUser)).thenThrow(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint ux_tbl_user_username_canonical; Key (user_name)"
		));

		assertThatThrownBy(() -> userService.changeUsername(
				userId,
				new UsernameChangeRequestDto(" Contested ")
		)).isInstanceOfSatisfying(SoundConnectException.class,
				exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.USER_ALREADY_EXISTS));
	}

	@Test
	void updateUser_AdminUsernameOverrideDoesNotConsumeOrResetSelfServiceCooldown() {
		LocalDateTime previousSelfServiceChange = FIXED_NOW.minusDays(1);
		existingUser.setUsernameChangedAt(previousSelfServiceChange);

		Boolean updated = userService.updateUser(
				userId,
				userId,
				new UserUpdateRequestDto("AdminCorrection", null, null, null)
		);

		assertThat(updated).isTrue();
		assertThat(existingUser.getUsername()).isEqualTo("admincorrection");
		assertThat(existingUser.getUsernameChangedAt()).isEqualTo(previousSelfServiceChange);
		verifyNoInteractions(usernameChangeTimeProvider);
	}
	
	/**
	 * email alanı gelirse:
	 * - User.email güncellenmeli
	 * - updatedAt set edilmeli
	 * - save çağrılmalı
	 * - method true dönmeli
	 */
	@Test
	void updateUser_WhenEmailChanged_ShouldUpdateAndSaveAndReturnTrue() {
		UserUpdateRequestDto dto = new UserUpdateRequestDto(null, null, " New@Example.COM ", null);
		
		Boolean updated = userService.updateUser(userId, userId, dto);
		
		assertThat(updated).isTrue();
		verify(userRepository).saveAndFlush(userCaptor.capture());
		
		User saved = userCaptor.getValue();
		assertThat(saved.getEmail()).isEqualTo("new@example.com");
		assertThat(saved.getUpdatedAt()).isNotNull();
		verify(userRepository).existsByEmailAndIdNot("new@example.com", userId);
	}

	@Test
	void updateUser_WhenCanonicalEmailBelongsToAnotherUser_ShouldReject() {
		when(userRepository.existsByEmailAndIdNot("taken@example.com", userId)).thenReturn(true);
		UserUpdateRequestDto dto = new UserUpdateRequestDto(null, null, " TAKEN@Example.com ", null);

		assertThatThrownBy(() -> userService.updateUser(userId, userId, dto))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining("Email already exists");

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void updateUser_RepairsListenerProfileInvariantAfterAdministrativeMutation() {
		UUID targetId = UUID.randomUUID();
		User listenerTarget = User.builder()
				.id(targetId)
				.username("listener")
				.email("old@example.com")
				.roles(Set.of(Role.builder().name("ROLE_LISTENER").build()))
				.build();
		when(userRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(listenerTarget));

		Boolean updated = userService.updateUser(
				userId,
				targetId,
				new UserUpdateRequestDto(null, null, "new@example.com", null)
		);

		assertThat(updated).isTrue();
		verify(listenerProfileProvisioner).ensureExistsForUpdate(targetId);
	}

	@Test
	void saveUser_CanonicalizesEmailBeforeLookupAndPersistence() {
		UUID roleId = UUID.randomUUID();
		UUID newUserId = UUID.randomUUID();
		Role role = Role.builder().id(roleId).name("ROLE_LISTENER").build();
		when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
		when(passwordEncoder.encode("password123")).thenReturn("encoded");
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User created = invocation.getArgument(0);
			created.setId(newUserId);
			return created;
		});
		UserSaveRequestDto dto = new UserSaveRequestDto(
				" LiStEnEr ", " Listener@Example.COM ", roleId, "password123"
		);

		User saved = userService.saveUser(userId, dto);

		assertThat(saved.getEmail()).isEqualTo("listener@example.com");
		assertThat(saved.getUsername()).isEqualTo("listener");
		verify(userRepository).existsByUsername("listener");
		verify(userRepository).existsByEmail("listener@example.com");
		verify(listenerProfileProvisioner).ensureExistsForUpdate(newUserId);
		verifyNoInteractions(personalProfileTypePolicy);
	}
	
	/**
	 * password alanı gelirse:
	 * - PasswordEncoder.encode çağrılmalı
	 * - password hash ile güncellenmeli
	 * - updatedAt set edilmeli
	 * - save çağrılmalı
	 * - method true dönmeli
	 */
	@Test
	void updateUser_WhenPasswordChanged_ShouldEncodeAndSaveAndReturnTrue() {
		UserUpdateRequestDto dto = new UserUpdateRequestDto(null, "plain-pass", null, null);
		
		// Encoder'ın nasıl davranacağını belirliyoruz
		when(passwordEncoder.encode("plain-pass")).thenReturn("encoded-pass");
		
		Boolean updated = userService.updateUser(userId, userId, dto);
		
		assertThat(updated).isTrue();
		verify(passwordEncoder).encode("plain-pass");        // gerçekten encode edildi mi?
		verify(userRepository).saveAndFlush(userCaptor.capture());
		
		User saved = userCaptor.getValue();
		assertThat(saved.getPassword()).isEqualTo("encoded-pass"); // düz şifre değil, hash kullanılmalı
		assertThat(saved.getUpdatedAt()).isNotNull();
	}
	
	/**
	 * roleId gelirse:
	 * - RoleRepository.findById çağrılmalı
	 * - Kullanıcının roller listesi temizlenip yeni rol eklenmeli
	 * - updatedAt set edilmeli
	 * - save çağrılmalı
	 * - method true dönmeli
	 */
	@Test
	void updateUser_WhenRoleChanged_ShouldReplaceRolesAndSaveAndReturnTrue() {
		UUID roleId = UUID.randomUUID();
		Role newRole = Role.builder().id(roleId).name("ROLE_ADMIN").build();
		Role ownerRole = existingUser.getRoles().iterator().next();
		
		// Rol bulundu senaryosu
		when(roleRepository.findById(roleId)).thenReturn(Optional.of(newRole));
		when(roleRepository.findByNameForUpdate("ROLE_OWNER")).thenReturn(Optional.of(ownerRole));
		when(userRepository.countDistinctByRoles_Name("ROLE_OWNER")).thenReturn(2L);
		
		UserUpdateRequestDto dto = new UserUpdateRequestDto(null, null, null, roleId);
		
		Boolean updated = userService.updateUser(userId, userId, dto);
		
		assertThat(updated).isTrue();
		verify(roleRepository).findById(roleId);             // rol lookup yapıldı mı?
		verify(userRepository).saveAndFlush(userCaptor.capture());
		
		User saved = userCaptor.getValue();
		// Koleksiyonun tamamen değiştiğini garanti et (tek rol ve adı ROLE_ADMIN olmalı)
		assertThat(saved.getRoles())
				.hasSize(1)
				.extracting("name")
				.containsExactly("ROLE_ADMIN");
		assertThat(saved.getUpdatedAt()).isNotNull();
		verify(personalProfileTypePolicy).assertRoleReplacementAllowed(existingUser, newRole);
	}

	@Test
	void updateUser_WhenChangingAnExistingPersonalProfileRole_ShouldReject() {
		UUID targetId = UUID.randomUUID();
		UUID replacementRoleId = UUID.randomUUID();
		User listenerTarget = User.builder()
				.id(targetId)
				.roles(Set.of(Role.builder().name("ROLE_LISTENER").build()))
				.build();
		Role musicianRole = Role.builder()
				.id(replacementRoleId)
				.name("ROLE_MUSICIAN")
				.build();

		when(userRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(listenerTarget));
		when(roleRepository.findById(replacementRoleId)).thenReturn(Optional.of(musicianRole));
		doThrow(new SoundConnectException(ErrorType.PROFILE_TYPE_IMMUTABLE))
				.when(personalProfileTypePolicy)
				.assertRoleReplacementAllowed(listenerTarget, musicianRole);

		assertThatThrownBy(() -> userService.updateUser(
				userId,
				targetId,
				new UserUpdateRequestDto(null, null, null, replacementRoleId)
		)).isInstanceOfSatisfying(SoundConnectException.class,
				exception -> assertThat(exception.getErrorType())
						.isEqualTo(ErrorType.PROFILE_TYPE_IMMUTABLE));

		assertThat(listenerTarget.getRoles())
				.extracting(Role::getName)
				.containsExactly("ROLE_LISTENER");
		verify(userRepository, never()).saveAndFlush(listenerTarget);
	}

	@Test
	void updateUser_WhenAssigningPersonalProfileRoleWithoutOnboarding_ShouldReject() {
		UUID replacementRoleId = UUID.randomUUID();
		Role listenerRole = Role.builder()
				.id(replacementRoleId)
				.name("ROLE_LISTENER")
				.build();
		Role ownerRole = existingUser.getRoles().iterator().next();
		when(roleRepository.findById(replacementRoleId)).thenReturn(Optional.of(listenerRole));
		when(roleRepository.findByNameForUpdate("ROLE_OWNER")).thenReturn(Optional.of(ownerRole));
		when(userRepository.countDistinctByRoles_Name("ROLE_OWNER")).thenReturn(2L);
		doThrow(new SoundConnectException(ErrorType.PROFILE_TYPE_IMMUTABLE))
				.when(personalProfileTypePolicy)
				.assertRoleReplacementAllowed(existingUser, listenerRole);

		assertThatThrownBy(() -> userService.updateUser(
				userId,
				userId,
				new UserUpdateRequestDto(null, null, null, replacementRoleId)
		)).isInstanceOfSatisfying(SoundConnectException.class,
				exception -> assertThat(exception.getErrorType())
						.isEqualTo(ErrorType.PROFILE_TYPE_IMMUTABLE));

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void updateUser_WhenDowngradingLastOwner_ShouldLockInvariantAndReject() {
		UUID roleId = UUID.randomUUID();
		Role replacement = Role.builder().id(roleId).name("ROLE_ADMIN").build();
		Role ownerRole = existingUser.getRoles().iterator().next();
		when(roleRepository.findById(roleId)).thenReturn(Optional.of(replacement));
		when(roleRepository.findByNameForUpdate("ROLE_OWNER")).thenReturn(Optional.of(ownerRole));
		when(userRepository.countDistinctByRoles_Name("ROLE_OWNER")).thenReturn(1L);

		assertThatThrownBy(() -> userService.updateUser(
				userId,
				userId,
				new UserUpdateRequestDto(null, null, null, roleId)
		)).isInstanceOf(SoundConnectException.class);

		InOrder invariantOrder = inOrder(roleRepository, userRepository);
		invariantOrder.verify(roleRepository).findByNameForUpdate("ROLE_OWNER");
		invariantOrder.verify(userRepository).countDistinctByRoles_Name("ROLE_OWNER");
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void ownerInvariantFinderDeclaresPessimisticWriteLock() throws NoSuchMethodException {
		Lock lock = RoleRepository.class
				.getMethod("findByNameForUpdate", String.class)
				.getAnnotation(Lock.class);

		assertThat(lock).isNotNull();
		assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}

	@Test
	void selfServiceUsernameFinderDeclaresPessimisticWriteLock() throws NoSuchMethodException {
		Lock lock = UserRepository.class
				.getMethod("findByIdForUpdate", UUID.class)
				.getAnnotation(Lock.class);

		assertThat(lock).isNotNull();
		assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}

	@Test
	void deleteUserById_LocksDifferentUsersInStableUuidOrder() {
		UUID lowerTargetId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID higherActorId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		User ownerActor = User.builder()
		                           .id(higherActorId)
		                           .roles(Set.of(Role.builder().name("ROLE_OWNER").build()))
		                           .build();
		User regularTarget = User.builder()
		                              .id(lowerTargetId)
		                              .roles(Set.of(Role.builder().name("ROLE_USER").build()))
		                              .build();
		when(userRepository.findByIdForUpdate(lowerTargetId)).thenReturn(Optional.of(regularTarget));
		when(userRepository.findByIdForUpdate(higherActorId)).thenReturn(Optional.of(ownerActor));

		userService.deleteUserById(higherActorId, lowerTargetId);

		InOrder lockOrder = inOrder(userRepository);
		lockOrder.verify(userRepository).findByIdForUpdate(lowerTargetId);
		lockOrder.verify(userRepository).findByIdForUpdate(higherActorId);
		verify(userRepository).delete(regularTarget);
	}
	
	/**
	 * roleId verilmiş ama role bulunamamışsa:
	 * - SoundConnectException fırlatılmalı
	 * - save asla çağrılmamalı (yanlışlıkla persist olmasın)
	 */
	@Test
	void updateUser_WhenRoleIdNotFound_ShouldThrow() {
		UUID roleId = UUID.randomUUID();
		// Rol bulunamadı senaryosu
		when(roleRepository.findById(roleId)).thenReturn(Optional.empty());
		
		UserUpdateRequestDto dto = new UserUpdateRequestDto(null, null, null, roleId);
		
		// Doğru tipte exception bekliyoruz
		assertThatThrownBy(() -> userService.updateUser(userId, userId, dto))
				.isInstanceOf(SoundConnectException.class);
		
		// Hata fırladıysa save çağrısı olmamalı
		verify(userRepository, never()).saveAndFlush(any());
	}
	
	/**
	 * DTO'da hiçbir alan yoksa:
	 * - Güncelleme yapılmamalı
	 * - save çağrılmamalı
	 * - method false dönmeli
	 */
	@Test
	void updateUser_WhenNoFieldsProvided_ShouldReturnFalseAndNotSave() {
		UserUpdateRequestDto dto = new UserUpdateRequestDto(null, null, null, null);
		
		Boolean updated = userService.updateUser(userId, userId, dto);
		
		assertThat(updated).isFalse();                      // hiçbir alan gelmedi => false
		verify(userRepository, never()).saveAndFlush(any());        // persiste gerek yok
	}
}
