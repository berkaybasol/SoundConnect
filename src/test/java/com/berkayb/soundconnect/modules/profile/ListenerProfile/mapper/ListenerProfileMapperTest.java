package com.berkayb.soundconnect.modules.profile.ListenerProfile.mapper;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class ListenerProfileMapperTest {
	
	private final ListenerProfileMapper mapper = Mappers.getMapper(ListenerProfileMapper.class);
	
	@Test
	void toDto_should_map_all_fields() {
		UUID userId = UUID.randomUUID();
		User u = User.builder()
		             .id(userId)
		             .username("alice")
		             .password("pw")
		             .build();
		
		ListenerProfile entity = ListenerProfile.builder()
		                                        .id(UUID.randomUUID())
		                                        .user(u)
		                                        .description("about me")
		                                        .profilePicture("pic.png")
		                                        .build();
		
		ListenerProfileResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto.id()).isEqualTo(entity.getId());
		assertThat(dto.userId()).isEqualTo(userId);
		assertThat(dto.profilePicture()).isEqualTo("pic.png");
		assertThat(dto.bio()).isEqualTo("about me"); // <- description -> bio
	}
}