package com.berkayb.soundconnect.modules.profile.StudioProfille.mapper;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.mapper.StudioProfileMapper;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class StudioProfileMapperTest {
	
	private final StudioProfileMapper mapper = Mappers.getMapper(StudioProfileMapper.class);
	
	@Test
	void toDto_should_map_userId_and_facilities() {
		UUID userId = UUID.randomUUID();
		User user = User.builder()
		                .username("studioUser")
		                .password("pwd")
		                .build();
		// user id'yi mocklamadan gerçek entity ile kullanmak istiyorsak setter varsa set edebiliriz
		// çoğu projede BaseEntity#setId protected olur; o yüzden userId eşleşmesini sadece dto üstünden kontrol edeceğiz
		// ama burada null değil istiyorsak mocklamak yerine repo/service testleriyle garanti ederiz.
		
		StudioProfile entity = StudioProfile.builder()
		                                    .user(user)
		                                    .description("pro studio")
		                                    .profilePicture("studio.png")
		                                    .facilities(Set.of("Piano", "Drums"))
		                                    .build();
		
		// MapStruct user.id -> userId map'liyor
		StudioProfileResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto).isNotNull();
		assertThat(dto.description()).isEqualTo("pro studio");
		assertThat(dto.profilePicture()).isEqualTo("studio.png");
		assertThat(dto.facilities()).containsExactlyInAnyOrder("Piano", "Drums");
	}
}