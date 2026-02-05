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
	void toDto_should_map_facilities_and_profilePictureMediaId() {
		// Arrange
		UUID ppId = UUID.randomUUID();
		
		User user = User.builder()
		                .username("studioUser")
		                .password("pwd")
		                .build();
		
		StudioProfile entity = StudioProfile.builder()
		                                    .user(user)
		                                    .description("pro studio")
		                                    .profilePictureMediaId(ppId)
		                                    .facilities(Set.of("Piano", "Drums"))
		                                    .build();
		
		// Act
		StudioProfileResponseDto dto = mapper.toDto(entity);
		
		// Assert
		assertThat(dto).isNotNull();
		assertThat(dto.description()).isEqualTo("pro studio");
		assertThat(dto.profilePictureMediaId()).isEqualTo(ppId); // ✅ aynı UUID
		assertThat(dto.facilities()).containsExactlyInAnyOrder("Piano", "Drums");
	}
}