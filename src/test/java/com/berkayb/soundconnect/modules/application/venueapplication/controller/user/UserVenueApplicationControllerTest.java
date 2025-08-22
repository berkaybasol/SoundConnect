package com.berkayb.soundconnect.modules.application.venueapplication.controller.user;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource; // -> eklendi

import java.util.UUID;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// === test config ===
@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestPropertySource(properties = { // -> eklendi
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", // -> eklendi
		"spring.jpa.hibernate.ddl-auto=create-drop" // -> eklendi
})
class UserVenueApplicationControllerTest {
	
	@Autowired MockMvc mockMvc;
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	@Autowired UserRepository userRepository;
	@Autowired VenueApplicationRepository venueApplicationRepository;
	
	// MailProducerImpl ihtiyacı
	@MockitoBean
	RabbitTemplate rabbitTemplate;
	
	private final ObjectMapper om = new ObjectMapper();
	
	private City city;
	private District district;
	private Neighborhood neighborhood;
	private User applicant;
	
	@BeforeEach
	void setUp() {
		// 1) Child tablolar önce
		venueApplicationRepository.deleteAllInBatch();
		
		// 2) Sonra kullanıcı ve location
		userRepository.deleteAll();
		neighborhoodRepository.deleteAllInBatch();
		districtRepository.deleteAllInBatch();
		cityRepository.deleteAllInBatch();
		
		// --- seed location ---
		city = cityRepository.save(City.builder().name("TestCity_" + UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("TestDistrict").city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("TestNeighborhood").district(district).build());
		
		// --- seed user ---
		applicant = userRepository.save(
				User.builder()
				    .username("user_" + UUID.randomUUID())
				    .email("user_"+UUID.randomUUID()+"@test.local") // -> eklendi
				    .password("secret")
				    .provider(com.berkayb.soundconnect.modules.user.enums.AuthProvider.LOCAL)
				    .emailVerified(true)
				    .city(city)
				    .build()
		);
		
		// --- SecurityContext'e principal koy ---
		var principal = new UserDetailsImpl(applicant);
		var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(auth);
	}
	
	@Test
	void createVenueApplication_ok() throws Exception {
		String body = """
                {
                  "venueName": "Cool Venue",
                  "venueAddress": "Some Address 123",
                  "cityId": "%s",
                  "districtId": "%s",
                  "neighborhoodId": "%s"
                }
                """.formatted(city.getId(), district.getId(), neighborhood.getId());
		
		mockMvc.perform(post("/api/v1/user/venue-applications/create")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isOk())                 // HTTP 200
		       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201))   // body.code 201
		       .andExpect(jsonPath("$.data.id").isNotEmpty())
		       .andExpect(jsonPath("$.data.venueName").value("Cool Venue"))
		       .andExpect(jsonPath("$.data.venueAddress").value("Some Address 123"))
		       .andExpect(jsonPath("$.data.status").value("PENDING"))
		       .andExpect(jsonPath("$.data.applicantUsername").value(applicant.getUsername()));
	}
	
	@Test
	void createVenueApplication_duplicate_should_return_4xx() throws Exception {
		String body = """
                {
                  "venueName": "Cool Venue",
                  "venueAddress": "Some Address 123",
                  "cityId": "%s",
                  "districtId": "%s",
                  "neighborhoodId": "%s"
                }
                """.formatted(city.getId(), district.getId(), neighborhood.getId());
		
		// 1) İlk istek başarılı
		mockMvc.perform(post("/api/v1/user/venue-applications/create")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.code").value(201));
		
		// 2) Aynı payload -> 4xx (409 veya 400 olabilir)
		mockMvc.perform(post("/api/v1/user/venue-applications/create")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().is4xxClientError())
		       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.httpStatus", anyOf(is("BAD_REQUEST"), is("CONFLICT"))))
		       .andExpect(jsonPath("$.message", containsString("already")))
		       .andExpect(jsonPath("$.message", containsString("application")));
	}
}