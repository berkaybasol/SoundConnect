package com.berkayb.soundconnect.modules.profile.ProducerProfile.mapper;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class ProducerProfileMapperTest {
	
	private final ProducerProfileMapper mapper = Mappers.getMapper(ProducerProfileMapper.class);
	
	@Test
	void toDto_should_map_all_fields() {
		UUID userId = UUID.randomUUID();
		User u = User.builder()
		             .id(userId)
		             .username("alice")
		             .password("pw")
		             .build();
		
		ProducerProfile entity = ProducerProfile.builder()
		                                        .id(UUID.randomUUID())
		                                        .user(u)
		                                        .name("Prod")
		                                        .description("desc")
		                                        .profilePicture("pic.png")
		                                        .address("addr")
		                                        .phone("555")
		                                        .website("site.com")
		                                        .instagramUrl("ig")
		                                        .youtubeUrl("yt")
		                                        .build();
		
		ProducerProfileResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto.id()).isEqualTo(entity.getId());
		assertThat(dto.name()).isEqualTo("Prod");
		assertThat(dto.description()).isEqualTo("desc");
		assertThat(dto.profilePicture()).isEqualTo("pic.png");
		assertThat(dto.address()).isEqualTo("addr");
		assertThat(dto.phone()).isEqualTo("555");
		assertThat(dto.website()).isEqualTo("site.com");
		assertThat(dto.instagramUrl()).isEqualTo("ig");
		assertThat(dto.youtubeUrl()).isEqualTo("yt");
	}
}