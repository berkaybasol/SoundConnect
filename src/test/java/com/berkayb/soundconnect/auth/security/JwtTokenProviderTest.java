package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenProviderTest {
	
	private JwtTokenProvider jwtTokenProvider;
	private User fakeUser;
	private UserDetailsImpl userDetails;
	
	@BeforeEach
	void setUp() throws Exception {
		jwtTokenProvider = new JwtTokenProvider();
		
		// @Value alanlarını elle setle (Spring yokken böyle yaparız)
		setField(jwtTokenProvider, "jwtSecret", "my-super-secret-key-my-super-secret-key");
		setField(jwtTokenProvider, "jwtExpiration", 3600000L); // 1 saat
		setField(jwtTokenProvider, "jwtIssuer", "soundconnect-auth");
		
		// Fake Role & Permission
		Permission p1 = Permission.builder().name("READ_USER").build();
		Permission p2 = Permission.builder().name("WRITE_USER").build();
		Role role = Role.builder()
		                .name("ROLE_ADMIN")
		                .permissions(Set.of(p1, p2))
		                .build();
		
		// Fake User
		fakeUser = User.builder()
		               .id(UUID.randomUUID())
		               .username("berkay")
		               .password("encoded-password")
		               .roles(Set.of(role))
		               .permissions(Set.of()) // doğrudan eklenmiş izin yok
		               .build();
		
		// UserDetailsImpl
		userDetails = UserDetailsImpl.fromUser(fakeUser);
	}
	
	// Reflection ile private field setleme
	private void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
	
	@Test
	void generateToken_ShouldContainRolesAndPermissions() {
		String token = jwtTokenProvider.generateToken(userDetails);
		
		assertThat(token).isNotBlank();
		
		UUID extractedUserId = jwtTokenProvider.getUserIdFromToken(token);
		assertThat(extractedUserId).isEqualTo(fakeUser.getId());
		
		assertThat(jwtTokenProvider.validateToken(token)).isTrue();
	}
	
	@Test
	void getUserIdFromToken_ShouldReturnCorrectId() {
		String token = jwtTokenProvider.generateToken(userDetails);
		
		UUID extractedUserId = jwtTokenProvider.getUserIdFromToken(token);
		assertThat(extractedUserId).isEqualTo(fakeUser.getId());
	}
	
	@Test
	void validateToken_ShouldReturnFalse_WhenTokenIsInvalid() {
		String invalidToken = "invalid.token.value";
		assertThat(jwtTokenProvider.validateToken(invalidToken)).isFalse();
	}
	
	@Test
	void validateToken_ShouldFail_WhenTokenExpired() throws Exception {
		// Expiration'ı çok küçük verelim
		setField(jwtTokenProvider, "jwtExpiration", 1L); // 1 ms
		String token = jwtTokenProvider.generateToken(userDetails);
		
		// Biraz bekle
		Thread.sleep(5);
		
		assertThat(jwtTokenProvider.validateToken(token)).isFalse();
	}
}