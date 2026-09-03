package com.berkayb.soundconnect.modules.profile.ListenerProfile.mapper;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
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
		UUID ppId = UUID.randomUUID();
		
		User u = User.builder()
		             .id(userId)
		             .username("alice")
		             .password("pw")
		             .build();
		
		ListenerProfile entity = ListenerProfile.builder()
		                                        .id(UUID.randomUUID())
		                                        .user(u)
		                                        .description("about me")
		                                        .profilePictureMediaId(ppId)
		                                        .visibilityMode(ListenerVisibilityMode.GHOST)
		                                        .version(7L)
		                                        .build();
		
		ListenerProfileResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto.id()).isEqualTo(entity.getId());
		assertThat(dto.userId()).isEqualTo(userId);
		assertThat(dto.profilePictureMediaId()).isEqualTo(ppId);
		assertThat(dto.bio()).isEqualTo("about me"); // description -> bio
		assertThat(dto.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
		assertThat(dto.version()).isEqualTo(7L);
	}
}
