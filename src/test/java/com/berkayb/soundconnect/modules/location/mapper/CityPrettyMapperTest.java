package com.berkayb.soundconnect.modules.location.mapper;

import com.berkayb.soundconnect.modules.location.dto.response.CityPrettyDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CityPrettyMapperTest {

	private final CityPrettyMapper mapper = new CityPrettyMapper();

	@Test
	void sortsNestedDistrictsAndNeighborhoods() {
		District cankaya = District.builder()
				.name("Çankaya")
				.neighborhoods(new ArrayList<>(List.of(
						Neighborhood.builder().name("100. Yıl").build(),
						Neighborhood.builder().name("2 Eylül").build(),
						Neighborhood.builder().name("10 Ekim").build()
				)))
				.build();
		District ceyhan = District.builder()
				.name("Ceyhan")
				.neighborhoods(new ArrayList<>(List.of(
						Neighborhood.builder().name("Ödemiş").build(),
						Neighborhood.builder().name("Osmangazi").build()
				)))
				.build();
		City city = City.builder()
				.name("Adana")
				.districts(new ArrayList<>(List.of(cankaya, ceyhan)))
				.build();

		CityPrettyDto result = mapper.toPretty(city);

		assertThat(result.districts()).extracting(district -> district.name())
				.containsExactly("Ceyhan", "Çankaya");
		assertThat(result.districts().get(0).neighborhoods())
				.containsExactly("Osmangazi", "Ödemiş");
		assertThat(result.districts().get(1).neighborhoods())
				.containsExactly("2 Eylül", "10 Ekim", "100. Yıl");
	}
}
