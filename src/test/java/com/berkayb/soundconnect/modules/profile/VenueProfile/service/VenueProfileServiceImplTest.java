package com.berkayb.soundconnect.modules.profile.VenueProfile.service;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
// İzole H2 ve net şema yönetimi
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("service")
class VenueProfileServiceImplTest {
	
	@MockitoBean
	RabbitTemplate rabbitTemplate;
	
	@org.springframework.beans.factory.annotation.Autowired
	VenueProfileService venueProfileService;
	
	@org.springframework.beans.factory.annotation.Autowired
	VenueProfileRepository venueProfileRepository;
	@org.springframework.beans.factory.annotation.Autowired
	VenueRepository venueRepository;
	@org.springframework.beans.factory.annotation.Autowired
	UserRepository userRepository;
	@org.springframework.beans.factory.annotation.Autowired
	CityRepository cityRepository;
	@org.springframework.beans.factory.annotation.Autowired
	DistrictRepository districtRepository;
	@org.springframework.beans.factory.annotation.Autowired
	NeighborhoodRepository neighborhoodRepository;
	
	City city;
	District district;
	Neighborhood neighborhood;
	User ownerA;
	User ownerB;
	Venue venueA1; // ownerA'nın mekanı
	Venue venueA2; // ownerA'nın 2. mekanı
	Venue venueB1; // ownerB'nin mekanı
	
	@BeforeEach
	void setup() {
		// FK sırası doğru
		venueProfileRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		// location
		city = cityRepository.save(City.builder().name("City_" + UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("District_" + UUID.randomUUID()).city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("Neighborhood_" + UUID.randomUUID()).district(district).build());
		
		// users (email eklendi)
		ownerA = userRepository.save(User.builder()
		                                 .username("ownerA_" + UUID.randomUUID())
		                                 .email("ownera_"+UUID.randomUUID()+"@t.local")
		                                 .password("pass")
		                                 .provider(AuthProvider.LOCAL)
		                                 .emailVerified(true)
		                                 .city(city)
		                                 .build());
		
		ownerB = userRepository.save(User.builder()
		                                 .username("ownerB_" + UUID.randomUUID())
		                                 .email("ownerb_"+UUID.randomUUID()+"@t.local")
		                                 .password("pass")
		                                 .provider(AuthProvider.LOCAL)
		                                 .emailVerified(true)
		                                 .city(city)
		                                 .build());
		
		// venues
		venueA1 = venueRepository.save(Venue.builder()
		                                    .name("A1")
		                                    .address("addr A1")
		                                    .city(city)
		                                    .district(district)
		                                    .neighborhood(neighborhood)
		                                    .owner(ownerA)
		                                    .phone("5001112233")
		                                    .status(VenueStatus.APPROVED)
		                                    .build());
		
		venueA2 = venueRepository.save(Venue.builder()
		                                    .name("A2")
		                                    .address("addr A2")
		                                    .city(city)
		                                    .district(district)
		                                    .neighborhood(neighborhood)
		                                    .owner(ownerA)
		                                    .phone("5001112244")
		                                    .status(VenueStatus.APPROVED)
		                                    .build());
		
		venueB1 = venueRepository.save(Venue.builder()
		                                    .name("B1")
		                                    .address("addr B1")
		                                    .city(city)
		                                    .district(district)
		                                    .neighborhood(neighborhood)
		                                    .owner(ownerB)
		                                    .phone("5001112255")
		                                    .status(VenueStatus.APPROVED)
		                                    .build());
	}
	
	private VenueProfileSaveRequestDto dto(String bio) {
		return new VenueProfileSaveRequestDto(
				bio,
				"pic.png",
				"https://instagram.com/x",
				"https://youtube.com/x",
				"https://site.com"
		);
	}
	
	@Test
	void createProfile_success() {
		VenueProfileResponseDto res = venueProfileService.createProfile(venueA1.getId(), dto("hello"));
		assertThat(res).isNotNull();
		assertThat(res.venueId()).isEqualTo(venueA1.getId());
		assertThat(res.venueName()).isEqualTo("A1");
		assertThat(res.bio()).isEqualTo("hello");
		
		// repo’da gerçekten oluştu mu
		assertThat(venueProfileRepository.findByVenueId(venueA1.getId())).isPresent();
	}
	
	@Test
	void createProfile_duplicate_shouldThrow() {
		// önce oluştur
		venueProfileService.createProfile(venueA1.getId(), dto("p1"));
		
		// aynı venue için tekrar -> PROFILE_ALREADY_EXISTS
		assertThatThrownBy(() -> venueProfileService.createProfile(venueA1.getId(), dto("p2")))
				.isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void getProfileByVenueId_notFound_shouldThrow() {
		assertThatThrownBy(() -> venueProfileService.getProfileByVenueId(venueA1.getId()))
				.isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void updateProfile_success() {
		// önce yarat
		venueProfileService.createProfile(venueA1.getId(), dto("old"));
		
		// update
		VenueProfileResponseDto updated = venueProfileService.updateProfile(venueA1.getId(),
		                                                                    new VenueProfileSaveRequestDto("new bio", null, null, null, "https://new.site"));
		
		assertThat(updated.bio()).isEqualTo("new bio");
		assertThat(updated.websiteUrl()).isEqualTo("https://new.site");
		
		VenueProfile inDb = venueProfileRepository.findByVenueId(venueA1.getId()).orElseThrow();
		assertThat(inDb.getBio()).isEqualTo("new bio");
		assertThat(inDb.getWebsiteUrl()).isEqualTo("https://new.site");
	}
	
	@Test
	void getProfilesByUserId_returnsOnlyExistingProfiles() {
		// ownerA: 2 venue var ama sadece birine profil oluşturuyoruz
		venueProfileService.createProfile(venueA1.getId(), dto("bioA1"));
		// venueA2 için profil yok
		
		List<VenueProfileResponseDto> list = venueProfileService.getProfilesByUserId(ownerA.getId());
		assertThat(list).hasSize(1);
		assertThat(list.get(0).venueId()).isEqualTo(venueA1.getId());
	}
	
	@Test
	void updateProfileByVenueId_wrongOwner_shouldThrow() {
		// önce B’nin mekanına profil oluştur
		venueProfileService.createProfile(venueB1.getId(), dto("b-bio"));
		
		// ownerA, ownerB’nin mekanını güncellemeye çalışıyor -> VENUE_NOT_FOUND fırlatır
		assertThatThrownBy(() ->
				                   venueProfileService.updateProfileByVenueId(ownerA.getId(), venueB1.getId(),
				                                                              new VenueProfileSaveRequestDto("hack", null, null, null, null)))
				.isInstanceOf(SoundConnectException.class);
	}
}