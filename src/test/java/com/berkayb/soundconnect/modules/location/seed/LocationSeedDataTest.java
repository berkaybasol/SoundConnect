package com.berkayb.soundconnect.modules.location.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.berkayb.soundconnect.modules.location.support.TurkishAlphabeticalOrder;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocationSeedDataTest {

	private static final List<String> EXPECTED_PROVINCES = List.of(
			"Adana", "Adıyaman", "Afyonkarahisar", "Ağrı", "Amasya", "Ankara", "Antalya", "Artvin",
			"Aydın", "Balıkesir", "Bilecik", "Bingöl", "Bitlis", "Bolu", "Burdur", "Bursa",
			"Çanakkale", "Çankırı", "Çorum", "Denizli", "Diyarbakır", "Edirne", "Elazığ", "Erzincan",
			"Erzurum", "Eskişehir", "Gaziantep", "Giresun", "Gümüşhane", "Hakkari", "Hatay", "Isparta",
			"Mersin", "İstanbul", "İzmir", "Kars", "Kastamonu", "Kayseri", "Kırklareli", "Kırşehir",
			"Kocaeli", "Konya", "Kütahya", "Malatya", "Manisa", "Kahramanmaraş", "Mardin", "Muğla",
			"Muş", "Nevşehir", "Niğde", "Ordu", "Rize", "Sakarya", "Samsun", "Siirt", "Sinop",
			"Sivas", "Tekirdağ", "Tokat", "Trabzon", "Tunceli", "Şanlıurfa", "Uşak", "Van", "Yozgat",
			"Zonguldak", "Aksaray", "Bayburt", "Karaman", "Kırıkkale", "Batman", "Şırnak", "Bartın",
			"Ardahan", "Iğdır", "Yalova", "Karabük", "Kilis", "Osmaniye", "Düzce"
	);

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void containsTheCompleteVersionedTurkeyHierarchy() throws Exception {
		List<CitySeedDto> cities = readSeed();

		assertThat(cities)
				.extracting(CitySeedDto::name)
				.containsExactlyInAnyOrderElementsOf(EXPECTED_PROVINCES)
				.doesNotHaveDuplicates();
		assertThat(cities)
				.extracting(CitySeedDto::name)
				.isSortedAccordingTo(TurkishAlphabeticalOrder.textComparator());
		assertThat(cities.stream().mapToInt(city -> city.districts().size()).sum()).isEqualTo(973);
		assertThat(cities.stream()
				.flatMap(city -> city.districts().stream())
				.mapToInt(district -> district.neighborhoods().size())
				.sum()).isEqualTo(32_254);

		cities.forEach(city -> {
			assertCanonicalName(city.name());
			assertThat(city.districts())
					.as("districts for %s", city.name())
					.isNotEmpty()
					.extracting(DistrictSeedDto::name)
					.doesNotHaveDuplicates();
			assertThat(city.districts())
					.as("district order for %s", city.name())
					.extracting(DistrictSeedDto::name)
					.isSortedAccordingTo(TurkishAlphabeticalOrder.textComparator());

			city.districts().forEach(district -> {
				assertCanonicalName(district.name());
				assertThat(district.neighborhoods())
						.as("neighborhoods for %s/%s", city.name(), district.name())
						.isNotEmpty()
						.doesNotHaveDuplicates()
						.isSortedAccordingTo(TurkishAlphabeticalOrder.textComparator());
				district.neighborhoods().forEach(this::assertCanonicalName);
			});
		});
	}

	@Test
	void keepsUniversityNightlifeAndTourismCentersComplete() throws Exception {
		List<CitySeedDto> cities = readSeed();

		assertCityCoverage(cities, "İstanbul", 39, 961);
		assertCityCoverage(cities, "Ankara", 25, 1_427);
		assertCityCoverage(cities, "İzmir", 30, 1_299);
		assertCityCoverage(cities, "Antalya", 19, 913);
		assertCityCoverage(cities, "Bursa", 17, 1_060);
		assertCityCoverage(cities, "Eskişehir", 14, 540);
		assertCityCoverage(cities, "Muğla", 13, 574);
		assertCityCoverage(cities, "Çanakkale", 12, 83);
		assertCityCoverage(cities, "Edirne", 9, 100);

		assertNeighborhoods(cities, "İstanbul", "Kadıköy", "Caferağa", "Caddebostan", "Fenerbahçe");
		assertNeighborhoods(cities, "Ankara", "Çankaya", "100.Yıl", "Bahçelievler", "Kocatepe");
		assertNeighborhoods(cities, "İzmir", "Konak", "Alsancak", "Göztepe", "Kültür");
		assertNeighborhoods(cities, "Muğla", "Bodrum", "Bitez", "Çarşı", "Yalıkavak");
	}

	@Test
	void disambiguatesSameNamedNeighborhoodsFromDifferentMunicipalities() throws Exception {
		List<CitySeedDto> cities = readSeed();

		assertNeighborhoods(
				cities,
				"Adıyaman",
				"Besni",
				"Fatih (Kesmetepe)",
				"Fatih (Şambayat)"
		);
	}

	private List<CitySeedDto> readSeed() throws Exception {
		try (InputStream inputStream = new ClassPathResource("location-seed.json").getInputStream()) {
			return objectMapper.readValue(inputStream, new TypeReference<>() {});
		}
	}

	private void assertCityCoverage(
			List<CitySeedDto> cities,
			String cityName,
			int expectedDistricts,
			int expectedNeighborhoods
	) {
		CitySeedDto city = findCity(cities, cityName);
		assertThat(city.districts()).hasSize(expectedDistricts);
		assertThat(city.districts().stream()
				.mapToInt(district -> district.neighborhoods().size())
				.sum()).isEqualTo(expectedNeighborhoods);
	}

	private void assertNeighborhoods(
			List<CitySeedDto> cities,
			String cityName,
			String districtName,
			String... expectedNeighborhoods
	) {
		CitySeedDto city = findCity(cities, cityName);
		DistrictSeedDto district = city.districts().stream()
				.filter(candidate -> candidate.name().equals(districtName))
				.findFirst()
				.orElseThrow();
		assertThat(district.neighborhoods()).contains(expectedNeighborhoods);
	}

	private CitySeedDto findCity(List<CitySeedDto> cities, String cityName) {
		return cities.stream()
				.filter(city -> city.name().equals(cityName))
				.findFirst()
				.orElseThrow();
	}

	private void assertCanonicalName(String name) {
		assertThat(name).isNotBlank().isEqualTo(name.strip());
		assertThat(Normalizer.isNormalized(name, Normalizer.Form.NFC))
				.as("NFC normalization for %s", name)
				.isTrue();
	}
}
