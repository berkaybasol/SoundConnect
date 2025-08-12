package com.berkayb.soundconnect.modules.profile.OrganizerProfile.mapper;

import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizerProfileMapperTest {
	
	private final OrganizerProfileMapper mapper = Mappers.getMapper(OrganizerProfileMapper.class);
	
	@Test
	void toDto_should_map_all_fields() {
		UUID userId = UUID.randomUUID();
		User u = User.builder()
		             .id(userId)
		             .username("alice")
		             .password("pw")
		             .build();
		
		OrganizerProfile entity = OrganizerProfile.builder()
		                                          .id(UUID.randomUUID())
		                                          .user(u)
		                                          .name("Org")
		                                          .description("desc")
		                                          .profilePicture("pic.png")
		                                          .address("addr")
		                                          .phone("555")
		                                          .instagramUrl("ig")
		                                          .youtubeUrl("yt")
		                                          .build();
		
		OrganizerProfileResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto.id()).isEqualTo(entity.getId());
		assertThat(dto.name()).isEqualTo("Org");
		assertThat(dto.description()).isEqualTo("desc");
		assertThat(dto.profilePicture()).isEqualTo("pic.png");
		assertThat(dto.address()).isEqualTo("addr");
		assertThat(dto.phone()).isEqualTo("555");
		assertThat(dto.instagramUrl()).isEqualTo("ig");
		assertThat(dto.youtubeUrl()).isEqualTo("yt");
	}
}