package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class ArtistVenueConnectionRequestMapperTest {
	
	private final ArtistVenueConnectionRequestMapper mapper =
			Mappers.getMapper(ArtistVenueConnectionRequestMapper.class);
	
	@Test
	void toResponseDto_should_map_all_fields() {
		UUID mpId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		
		MusicianProfile mp = MusicianProfile.builder()
		                                    .id(mpId)
		                                    .stageName("Cool Artist")
		                                    .build();
		
		Venue venue = Venue.builder()
		                   .id(venueId)
		                   .name("Nice Venue")
		                   .build();
		
		ArtistVenueConnectionRequest req = ArtistVenueConnectionRequest.builder()
		                                                               .musicianProfile(mp)
		                                                               .venue(venue)
		                                                               .status(RequestStatus.PENDING)
		                                                               .requestByType(RequestByType.ARTIST)
		                                                               .message("let's collab")
		                                                               .build();
		
		ArtistVenueConnectionRequestResponseDto dto = mapper.toResponseDto(req);
		
		assertThat(dto).isNotNull();
		assertThat(dto.musicianProfileId()).isEqualTo(mpId);
		assertThat(dto.musicianStageName()).isEqualTo("Cool Artist");
		assertThat(dto.venueId()).isEqualTo(venueId);
		assertThat(dto.venueName()).isEqualTo("Nice Venue");
		assertThat(dto.status()).isEqualTo(RequestStatus.PENDING.name());
		assertThat(dto.requestByType()).isEqualTo(RequestByType.ARTIST);
		assertThat(dto.message()).isEqualTo("let's collab");
		// createdAt null olabilir; ekstra assert etmiyoruz.
	}
}