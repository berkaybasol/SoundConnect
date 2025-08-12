package com.berkayb.soundconnect.modules.user.mapper;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
@Tag("mapper")
class UserMapperTest {
	
	private final UserMapper mapper = Mappers.getMapper(UserMapper.class);
	
	@Test
	void toDto_full_shouldMapAllFields() {
		// given
		UUID userId = UUID.randomUUID();
		City city = new City();
		city.setId(UUID.randomUUID());
		city.setName("Ankara");
		
		Role r1 = Role.builder().id(UUID.randomUUID()).name("ROLE_USER").build();
		Role r2 = Role.builder().id(UUID.randomUUID()).name("ROLE_ADMIN").build();
		
		// followers/following sayısını test etmek için mock Follow nesneleri
		Object f1 = mock(Object.class);
		Object f2 = mock(Object.class);
		Object f3 = mock(Object.class);
		
		User u = User.builder()
		             .id(userId)
		             .username("berkay")
		             .gender(Gender.MALE)
		             .city(city)
		             .emailVerified(true)
		             .roles(new HashSet<>(Set.of(r1, r2)))
		             .build();
		
		// LAZY koleksiyonlar: sadece size() kullanılacak, Set doldurmamız yeterli
		// Tip güvenliği için Set türleri User tarafında Follow; burada mock kullanıyoruz
		Set followers = new HashSet<>(Set.of(f1, f2)); // 2
		Set following = new HashSet<>(Set.of(f1, f2, f3)); // 3
		u.setFollowers(followers);
		u.setFollowing(following);
		
		// when
		UserListDto dto = mapper.toDto(u);
		
		// then
		assertThat(dto.id()).isEqualTo(userId);
		assertThat(dto.username()).isEqualTo("berkay");
		assertThat(dto.gender()).isEqualTo(Gender.MALE);
		
		// City -> CityResponseDto (id + name)
		assertThat(dto.city()).isNotNull();
		assertThat(dto.city().id()).isEqualTo(city.getId());
		assertThat(dto.city().name()).isEqualTo("Ankara");
		
		// followers/following boyutu doğru mu?
		assertThat(dto.followers()).isEqualTo(2);
		assertThat(dto.following()).isEqualTo(3);
		
		// role isimleri doğru set edilmiş mi?
		assertThat(dto.roles()).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
		
		// emailVerified mapper’da doğrudan field mapping (default MapStruct)
		assertThat(dto.emailVerified()).isTrue();
	}
	
	@Test
	void toDto_nullCollections_shouldReturnZerosAndNullSafe() {
		// given
		User u = User.builder()
		             .id(UUID.randomUUID())
		             .username("nullsafe")
		             .gender(Gender.OTHER)
		             .build();
		
		// null verip NPE atmamasını ve 0 dönmesini bekliyoruz
		u.setFollowers(null);
		u.setFollowing(null);
		u.setRoles(null); // roles null ise dto.roles da null olabilir → mapper öyle yazılmış
		
		// when
		UserListDto dto = mapper.toDto(u);
		
		// then
		assertThat(dto.username()).isEqualTo("nullsafe");
		assertThat(dto.followers()).isEqualTo(0);
		assertThat(dto.following()).isEqualTo(0);
		assertThat(dto.roles()).isNull(); // @Named mapRolesToNames null için null döndürüyor
	}
}