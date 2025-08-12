package com.berkayb.soundconnect.modules.venue.mapper;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.modules.venue.dto.response.VenueResponseDto;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Spy;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sadece MapStruct mapping'lerini doğrular.
 * - toEntity(dto, city, district, neighborhood, owner)
 * - toResponse(entity)
 * - toResponseList(list)
 *
 * Not: Mapper componentModel="spring" ama unit testte Spring konteyneri açmadan
 * Mappers.getMapper(...) ile doğrudan kullanıyoruz.
 */
class VenueMapperTest {
	
	@Spy
	private VenueMapper venueMapper = Mappers.getMapper(VenueMapper.class);
	
	@BeforeEach
	void setUp() {
		// MapStruct implementasyonu derleme sırasında üretilir.
		venueMapper = Mappers.getMapper(VenueMapper.class);
	}
	
	@Test
	void toEntity_should_map_dto_fields_and_relations() {
		// given
		UUID cityId = UUID.randomUUID();
		UUID districtId = UUID.randomUUID();
		UUID neighborhoodId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		
		City city = City.builder()
		                .id(cityId)
		                .name("Ankara")
		                .build();
		
		District district = District.builder()
		                            .id(districtId)
		                            .name("Çankaya")
		                            .city(city)
		                            .build();
		
		Neighborhood neighborhood = Neighborhood.builder()
		                                        .id(neighborhoodId)
		                                        .name("Bahçelievler")
		                                        .district(district)
		                                        .build();
		
		User owner = User.builder()
		                 .id(ownerId)
		                 .username("basol")
		                 .build();
		
		VenueRequestDto dto = new VenueRequestDto(
				"KaraKedi",
				"Adres satırı 123",
				cityId,
				districtId,
				neighborhoodId,
				ownerId,
				"05321234567",
				"https://karakedi.example",
				"Canlı müzik mekânı",
				"21:00"
		);
		
		// when
		Venue entity = venueMapper.toEntity(dto, city, district, neighborhood, owner);
		
		// then (DTO alanları)
		assertThat(entity.getName()).isEqualTo("KaraKedi");
		assertThat(entity.getAddress()).isEqualTo("Adres satırı 123");
		assertThat(entity.getPhone()).isEqualTo("05321234567");
		assertThat(entity.getWebsite()).isEqualTo("https://karakedi.example");
		assertThat(entity.getDescription()).isEqualTo("Canlı müzik mekânı");
		assertThat(entity.getMusicStartTime()).isEqualTo("21:00");
		
		// then (ilişkiler)
		assertThat(entity.getCity()).isSameAs(city);
		assertThat(entity.getDistrict()).isSameAs(district);
		assertThat(entity.getNeighborhood()).isSameAs(neighborhood);
		assertThat(entity.getOwner()).isSameAs(owner);
		
		// then (ignore edilen alanlar)
		assertThat(entity.getId()).isNull();
		assertThat(entity.getStatus()).isNull();
		assertThat(entity.getCreatedAt()).isNull();
		assertThat(entity.getUpdatedAt()).isNull();
	}
	
	@Test
	void toResponse_should_map_nested_fields_and_owner_info() {
		// given
		City city = City.builder()
		                .id(UUID.randomUUID())
		                .name("Ankara")
		                .build();
		
		District district = District.builder()
		                            .id(UUID.randomUUID())
		                            .name("Çankaya")
		                            .city(city)
		                            .build();
		
		Neighborhood neighborhood = Neighborhood.builder()
		                                        .id(UUID.randomUUID())
		                                        .name("Bahçelievler")
		                                        .district(district)
		                                        .build();
		
		User owner = User.builder()
		                 .id(UUID.randomUUID())
		                 .username("basol")
		                 .build();
		
		Venue venue = Venue.builder()
		                   .id(UUID.randomUUID())
		                   .name("KaraKedi")
		                   .address("Adres satırı 123")
		                   .phone("05321234567")
		                   .website("https://karakedi.example")
		                   .description("Canlı müzik mekânı")
		                   .musicStartTime("21:00")
		                   .city(city)
		                   .district(district)
		                   .neighborhood(neighborhood)
		                   .owner(owner)
		                   .build();
		
		// when
		VenueResponseDto res = venueMapper.toResponse(venue);
		
		// then (düz alanlar)
		assertThat(res.id()).isEqualTo(venue.getId());
		assertThat(res.name()).isEqualTo("KaraKedi");
		assertThat(res.address()).isEqualTo("Adres satırı 123");
		assertThat(res.phone()).isEqualTo("05321234567");
		assertThat(res.website()).isEqualTo("https://karakedi.example");
		assertThat(res.description()).isEqualTo("Canlı müzik mekânı");
		assertThat(res.musicStartTime()).isEqualTo("21:00");
		
		// then (iç içe alanlar)
		assertThat(res.cityName()).isEqualTo("Ankara");
		assertThat(res.districtName()).isEqualTo("Çankaya");
		assertThat(res.neighborhoodName()).isEqualTo("Bahçelievler");
		
		// then (owner bilgileri)
		assertThat(res.ownerId()).isEqualTo(owner.getId());
		assertThat(res.ownerFullName()).isEqualTo("basol");
		
		// not: activeMusicians alanı Venue üzerinde set edilmediyse null veya boş olabilir.
		// mapper default methodu null dönebilir; burada sadece null/boş olmamasını zorlamıyoruz.
	}
	
	@Test
	void toResponseList_should_map_list_elements() {
		// given
		City city = City.builder().id(UUID.randomUUID()).name("Ankara").build();
		District district = District.builder().id(UUID.randomUUID()).name("Çankaya").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder().id(UUID.randomUUID()).name("Bahçelievler").district(district).build();
		User owner = User.builder().id(UUID.randomUUID()).username("basol").build();
		
		Venue v1 = Venue.builder()
		                .id(UUID.randomUUID())
		                .name("KaraKedi")
		                .address("A1")
		                .city(city).district(district).neighborhood(neighborhood).owner(owner)
		                .build();
		
		Venue v2 = Venue.builder()
		                .id(UUID.randomUUID())
		                .name("SiyahBeyaz")
		                .address("A2")
		                .city(city).district(district).neighborhood(neighborhood).owner(owner)
		                .build();
		
		// when
		List<VenueResponseDto> list = venueMapper.toResponseList(List.of(v1, v2));
		
		// then
		assertThat(list).hasSize(2);
		assertThat(list.get(0).name()).isEqualTo("KaraKedi");
		assertThat(list.get(1).name()).isEqualTo("SiyahBeyaz");
		assertThat(list.get(0).cityName()).isEqualTo("Ankara");
		assertThat(list.get(1).districtName()).isEqualTo("Çankaya");
	}
}