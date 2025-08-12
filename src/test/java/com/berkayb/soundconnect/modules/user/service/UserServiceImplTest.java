package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.modules.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.mapper.UserMapper;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.security.crypto.password.PasswordEncoder;

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
	
	// ==== Bağımlılıklar (mock) ====
	@Mock private UserRepository userRepository;     // DB'ye gitmemek için sahte repo
	@Mock private RoleRepository roleRepository;     // Rol değişimi için sahte repo
	@Mock private UserMapper userMapper;             // Entity->DTO dönüşümü sahte
	@Mock private UserEntityFinder userEntityFinder; // update'te id'den user bulma sahte
	@Mock private PasswordEncoder passwordEncoder;   // parola hash'leme sahte
	@Mock private CityRepository cityRepository;     // ctor bağımlılığı; bu testte kullanılmıyor
	
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
		                   .build();
		
		// Finder default davranışı: her çağrıda aynı existingUser dönsün
		when(userEntityFinder.getUser(userId)).thenReturn(existingUser);
		
		// Repository.save(...) çağrıldığında, verilen argümanı aynen geri döndür (JPA davranışını taklit)
		when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
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
		UserUpdateRequestDto dto = new UserUpdateRequestDto("newName", null, null, null);
		
		// Çalıştır
		Boolean updated = userService.updateUser(userId, dto);
		
		// Beklenti: true + save çağrıldı + username değişti + updatedAt yazıldı
		assertThat(updated).isTrue();
		verify(userRepository).save(userCaptor.capture());   // kaydedilen User'ı yakala
		
		User saved = userCaptor.getValue();
		assertThat(saved.getUsername()).isEqualTo("newName");
		assertThat(saved.getUpdatedAt()).isNotNull();        // zaman damgası set edilmiş olmalı
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
		UserUpdateRequestDto dto = new UserUpdateRequestDto(null, null, "new@mail.com", null);
		
		Boolean updated = userService.updateUser(userId, dto);
		
		assertThat(updated).isTrue();
		verify(userRepository).save(userCaptor.capture());
		
		User saved = userCaptor.getValue();
		assertThat(saved.getEmail()).isEqualTo("new@mail.com");
		assertThat(saved.getUpdatedAt()).isNotNull();
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
		
		Boolean updated = userService.updateUser(userId, dto);
		
		assertThat(updated).isTrue();
		verify(passwordEncoder).encode("plain-pass");        // gerçekten encode edildi mi?
		verify(userRepository).save(userCaptor.capture());
		
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
		
		// Rol bulundu senaryosu
		when(roleRepository.findById(roleId)).thenReturn(Optional.of(newRole));
		
		UserUpdateRequestDto dto = new UserUpdateRequestDto(null, null, null, roleId);
		
		Boolean updated = userService.updateUser(userId, dto);
		
		assertThat(updated).isTrue();
		verify(roleRepository).findById(roleId);             // rol lookup yapıldı mı?
		verify(userRepository).save(userCaptor.capture());
		
		User saved = userCaptor.getValue();
		// Koleksiyonun tamamen değiştiğini garanti et (tek rol ve adı ROLE_ADMIN olmalı)
		assertThat(saved.getRoles())
				.hasSize(1)
				.extracting("name")
				.containsExactly("ROLE_ADMIN");
		assertThat(saved.getUpdatedAt()).isNotNull();
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
		assertThatThrownBy(() -> userService.updateUser(userId, dto))
				.isInstanceOf(SoundConnectException.class);
		
		// Hata fırladıysa save çağrısı olmamalı
		verify(userRepository, never()).save(any());
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
		
		Boolean updated = userService.updateUser(userId, dto);
		
		assertThat(updated).isFalse();                      // hiçbir alan gelmedi => false
		verify(userRepository, never()).save(any());        // persiste gerek yok
	}
}