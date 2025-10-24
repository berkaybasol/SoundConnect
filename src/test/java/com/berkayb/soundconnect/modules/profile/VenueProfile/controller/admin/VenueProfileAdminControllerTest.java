package com.berkayb.soundconnect.modules.profile.VenueProfile.controller.admin;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
// Her test sınıfı izole H2 + temiz şema
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("web")
class VenueProfileAdminControllerTest {
	
	@Autowired MockMvc mockMvc;
	
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	
	@Autowired UserRepository userRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired VenueProfileRepository venueProfileRepository;
	
	@Autowired VenueApplicationRepository venueApplicationRepository;
	
	// MailProducerImpl yüzünden gerekecek
	@MockitoBean RabbitTemplate rabbitTemplate;
	@MockitoBean
	RedisConnectionFactory redisConnectionFactory;
	@MockitoBean
	RedisTemplate<String, String> redisTemplate;
	@MockitoBean
	OtpService otpService;
	
	@MockitoBean
	StringRedisTemplate stringRedisTemplate;
	
	@MockitoBean
	MailJobHelper mailJobHelper;
	
	@MockitoBean
	MailSenderClient mailSenderClient;
	
	@MockitoBean
	org.springframework.amqp.support.converter.Jackson2JsonMessageConverter jackson2JsonMessageConverter;
	
	// Bazı config'ler ConnectionFactory isterse güvence:
	@MockitoBean(name = "rabbitConnectionFactory")
	org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory;
	
	
	// İstersen tüketiciyi de körle (gerekmeden geçmesi lazım ama garanti):
	@MockitoBean
	com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer dlqMailJobConsumer;
	
	City city;
	District district;
	Neighborhood neighborhood;
	User owner;
	Venue venue1;   // profilli
	Venue venue2;   // profilsiz
	VenueProfile profile1;
	
	@BeforeEach
	void setup() {
		// Temizlik sırası (FK güvenli)
		venueProfileRepository.deleteAll();
		venueRepository.deleteAll();
		venueApplicationRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		// Location seed
		city = cityRepository.save(City.builder().name("City_" + UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("District").city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("Neighborhood").district(district).build());
		
		// Owner (email zorunlu!)
		owner = userRepository.save(User.builder()
		                                .username("owner_" + UUID.randomUUID())
		                                .email("owner_" + UUID.randomUUID() + "@t.local")
		                                .password("pw")
		                                .provider(AuthProvider.LOCAL)
		                                .emailVerified(true)
		                                .city(city)
		                                .build());
		
		// Venues
		venue1 = venueRepository.save(Venue.builder()
		                                   .name("Venue One")
		                                   .address("Addr 1")
		                                   .city(city)
		                                   .district(district)
		                                   .neighborhood(neighborhood)
		                                   .owner(owner)
		                                   .phone("5550001111")
		                                   .status(VenueStatus.APPROVED)
		                                   .build());
		
		venue2 = venueRepository.save(Venue.builder()
		                                   .name("Venue Two")
		                                   .address("Addr 2")
		                                   .city(city)
		                                   .district(district)
		                                   .neighborhood(neighborhood)
		                                   .owner(owner)
		                                   .phone("5550002222")
		                                   .status(VenueStatus.APPROVED)
		                                   .build());
		
		// Profile for venue1
		profile1 = venueProfileRepository.save(VenueProfile.builder()
		                                                   .venue(venue1)
		                                                   .bio("old bio")
		                                                   .instagramUrl("https://ig/old")
		                                                   .build());
	}
	
	@Test
	void getProfilesByUserId_ok() throws Exception {
		// venue2 için de profil
		venueProfileRepository.save(VenueProfile.builder()
		                                        .venue(venue2)
		                                        .bio("bio two")
		                                        .build());
		
		mockMvc.perform(get("/api/v1/admin/venue-profiles/by-user/{userId}", owner.getId()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))))
		       .andExpect(jsonPath("$.data[*].venueId", hasItems(
				       venue1.getId().toString(),
				       venue2.getId().toString()
		       )));
	}
	
	@Test
	void adminUpdateProfile_ok() throws Exception {
		String body = """
        {
          "bio": "new bio by admin",
          "instagramUrl": "https://ig/new-admin",
          "youtubeUrl": "https://yt/new",
          "websiteUrl": "https://site/new"
        }
        """;
		
		mockMvc.perform(put("/api/v1/admin/venue-profiles/by-user/{userId}/{venueId}/update",
		                    owner.getId(), venue1.getId())
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.venueId").value(venue1.getId().toString()))
		       .andExpect(jsonPath("$.data.bio").value("new bio by admin"))
		       .andExpect(jsonPath("$.data.instagramUrl").value("https://ig/new-admin"))
		       .andExpect(jsonPath("$.data.youtubeUrl").value("https://yt/new"))
		       .andExpect(jsonPath("$.data.websiteUrl").value("https://site/new"));
	}
	
	@Test
	void adminCreateProfile_ok() throws Exception {
		// venue2 şu an profilsiz
		String body = """
        {
          "bio": "fresh bio",
          "profilePicture": "pic.png",
          "instagramUrl": "https://ig/fresh",
          "youtubeUrl": null,
          "websiteUrl": "https://fresh.site"
        }
        """;
		
		mockMvc.perform(post("/api/v1/admin/venue-profiles/create/{venueId}", venue2.getId())
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isOk())               // HTTP 200
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201)) // body.code 201
		       .andExpect(jsonPath("$.data.venueId").value(venue2.getId().toString()))
		       .andExpect(jsonPath("$.data.bio").value("fresh bio"))
		       .andExpect(jsonPath("$.data.instagramUrl").value("https://ig/fresh"))
		       .andExpect(jsonPath("$.data.websiteUrl").value("https://fresh.site"));
	}
	
	@Test
	void adminUpdateProfile_wrongUser_404() throws Exception {
		UUID wrongUserId = UUID.randomUUID();
		String body = """
        { "bio": "should not update" }
        """;
		
		mockMvc.perform(put("/api/v1/admin/venue-profiles/by-user/{userId}/{venueId}/update",
		                    wrongUserId, venue1.getId())
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isNotFound())
		       .andExpect(jsonPath("$.httpStatus").value("NOT_FOUND"));
	}
	
	@Test
	void adminCreateProfile_alreadyExists_400() throws Exception {
		String body = """
        { "bio": "dup" }
        """;
		
		mockMvc.perform(post("/api/v1/admin/venue-profiles/create/{venueId}", venue1.getId())
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"));
	}
}