package com.berkayb.soundconnect.modules.profile.VenueProfile.mapper;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class VenueProfileMapperTest {
	
	private final VenueProfileMapper mapper = Mappers.getMapper(VenueProfileMapper.class);
	
	@Test
	void toEntity_maps_fields_and_ignores_venue() {
		// given
		var dto = new VenueProfileSaveRequestDto(
				"bio text",
				"avatar.png",
				"https://instagram.com/x",
				"https://youtube.com/y",
				"https://mysite.example"
		);
		
		// when
		VenueProfile entity = mapper.toEntity(dto);
		
		// then
		assertThat(entity.getVenue()).isNull(); // venue ignore
		assertThat(entity.getBio()).isEqualTo("bio text");
		assertThat(entity.getProfilePicture()).isEqualTo("avatar.png");
		assertThat(entity.getInstagramUrl()).isEqualTo("https://instagram.com/x");
		assertThat(entity.getYoutubeUrl()).isEqualTo("https://youtube.com/y");
		assertThat(entity.getWebsiteUrl()).isEqualTo("https://mysite.example");
	}
	
	@Test
	void toResponse_maps_all_fields_including_venue_info() throws Exception {
		// given
		UUID vid = UUID.randomUUID();
		Venue venue = Venue.builder()
		                   .name("My Venue")
		                   .address("Addr")
		                   .build();
		setId(venue, vid); // test için ID’yi setliyoruz
		
		VenueProfile profile = VenueProfile.builder()
		                                   .venue(venue)
		                                   .bio("hello")
		                                   .profilePicture("pic.png")
		                                   .instagramUrl("insta")
		                                   .youtubeUrl("yt")
		                                   .websiteUrl("web")
		                                   .build();
		
		// when
		VenueProfileResponseDto dto = mapper.toResponse(profile);
		
		// then
		assertThat(dto.venueId()).isEqualTo(vid);
		assertThat(dto.venueName()).isEqualTo("My Venue");
		assertThat(dto.bio()).isEqualTo("hello");
		assertThat(dto.profilePicture()).isEqualTo("pic.png");
		assertThat(dto.instagramUrl()).isEqualTo("insta");
		assertThat(dto.youtubeUrl()).isEqualTo("yt");
		assertThat(dto.websiteUrl()).isEqualTo("web");
	}
	
	// ---- helpers ----
	private static void setId(Object entity, UUID id) throws Exception {
		Field f = findField(entity.getClass(), "id");
		f.setAccessible(true);
		f.set(entity, id);
	}
	
	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		Class<?> t = type;
		while (t != null) {
			try {
				return t.getDeclaredField(name);
			} catch (NoSuchFieldException ignore) {
				t = t.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}