package com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
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
		
		var profile = MusicianProfile.builder()
		                             .id(UUID.randomUUID())
		                             .stageName("Berkay Başol")
		                             .description("Fusion gitarist")
		                             .profilePicture("https://cdn/img.png")
		                             .instagramUrl("https://instagram.com/berkay")
		                             .youtubeUrl("https://youtube.com/berkay")
		                             .soundcloudUrl("https://soundcloud.com/berkay")
		                             .spotifyEmbedUrl("https://open.spotify.com/embed/...")
		                             .instruments(Set.of(instrument))
		                             .activeVenues(Set.of(venue))
		                             .build();
		
		MusicianProfileResponseDto dto = mapper.toDto(profile);
		
		assertThat(dto).isNotNull();
		assertThat(dto.id()).isEqualTo(profile.getId());
		assertThat(dto.stageName()).isEqualTo("Berkay Başol");
		assertThat(dto.bio()).isEqualTo("Fusion gitarist"); // description -> bio
		assertThat(dto.profilePicture()).isEqualTo("https://cdn/img.png");
		assertThat(dto.instagramUrl()).endsWith("/berkay");
		assertThat(dto.youtubeUrl()).contains("youtube");
		assertThat(dto.soundcloudUrl()).contains("soundcloud");
		assertThat(dto.spotifyEmbedUrl()).contains("spotify");
		assertThat(dto.instruments()).containsExactly("Guitar");
		assertThat(dto.activeVenues()).containsExactly("Ankara Rock Bar");
	}
}