package com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("mapper")
class MusicianProfileMapperTest {
	
	private final MusicianProfileMapper mapper = Mappers.getMapper(MusicianProfileMapper.class);
	
	@Test
	void toDto_shouldMapAllFields() {
		var instrument = Instrument.builder().name("Guitar").build();
		var venue = Venue.builder().name("Ankara Rock Bar").build();
		
		UUID profileId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID ppId = UUID.randomUUID();
		
		var user = User.builder().id(userId).build();
		
		var profile = MusicianProfile.builder()
		                             .id(profileId)
		                             .user(user)
		                             .stageName("Berkay Başol")
		                             .description("Fusion gitarist")
		                             .profilePictureMediaId(ppId)
		                             .instagramUrl("https://instagram.com/berkay")
		                             .youtubeUrl("https://youtube.com/berkay")
		                             .soundcloudUrl("https://soundcloud.com/berkay")
		                             .spotifyEmbedUrl("https://open.spotify.com/embed/...")
		                             .spotifyArtistId("artist123")
		                             .instruments(Set.of(instrument))
		                             .activeVenues(Set.of(venue))
		                             .build();
		
		MusicianProfileResponseDto dto = mapper.toDto(profile);
		
		assertThat(dto).isNotNull();
		assertThat(dto.id()).isEqualTo(profileId);
		assertThat(dto.userId()).isEqualTo(userId);
		
		assertThat(dto.stageName()).isEqualTo("Berkay Başol");
		assertThat(dto.bio()).isEqualTo("Fusion gitarist");
		assertThat(dto.profilePictureMediaId()).isEqualTo(ppId);
		
		assertThat(dto.instagramUrl()).isEqualTo("https://instagram.com/berkay");
		assertThat(dto.youtubeUrl()).isEqualTo("https://youtube.com/berkay");
		assertThat(dto.soundcloudUrl()).isEqualTo("https://soundcloud.com/berkay");
		assertThat(dto.spotifyEmbedUrl()).isEqualTo("https://open.spotify.com/embed/...");
		assertThat(dto.spotifyArtistId()).isEqualTo("artist123");
		
		// Set olduğu için containsExactly zorunlu değil; ama tek elemanla sorun yok.
		assertThat(dto.instruments()).contains("Guitar");
		assertThat(dto.activeVenues()).contains("Ankara Rock Bar");
		
		// Mapper bands’i ignore ediyor → null beklenir (MapStruct default)
		assertThat(dto.bands()).isNull();
	}
}