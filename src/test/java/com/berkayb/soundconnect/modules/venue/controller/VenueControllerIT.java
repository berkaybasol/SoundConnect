package com.berkayb.soundconnect.modules.venue.controller;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Venue.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false) // << security filter'ları kapat
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestMethodOrder(OrderAnnotation.class)
@Tag("web")
class VenueControllerIT {
	
	@Autowired MockMvc mockMvc;
	
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	@Autowired UserRepository userRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired RoleRepository roleRepository;
	
	
	@MockitoBean
	com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService venueProfileService;
	
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
	
	private UUID cityId;
	private UUID districtId;
	private UUID neighborhoodId;
	private UUID ownerId;
	
	@BeforeEach
	void seed() {
		// child -> parent sırası
		venueRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		// City/District/Neighborhood
		City city = cityRepository.save(City.builder().name("Ankara").build());
		cityId = city.getId();
		
		District district = districtRepository.save(District.builder().name("Çankaya").city(city).build());
		districtId = district.getId();
		
		Neighborhood neighborhood = neighborhoodRepository.save(
				Neighborhood.builder().name("Bahçelievler").district(district).build()
		);
		neighborhoodId = neighborhood.getId();
		
		// User (roles boş)
		User owner = userRepository.save(
				User.builder()
				    .username("basol")
				    .email("b@b.com")
				    .password("{noop}x")
				    .status(UserStatus.PENDING_VENUE_REQUEST)
				    .roles(new HashSet<>())
				    .build()
		);
		ownerId = owner.getId();
		
		// ROLE_VENUE (yoksa yarat)
		roleRepository.findByName("ROLE_VENUE")
		              .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_VENUE").build()));
	}
	
	// ---------- CREATE ----------
	@Test
	@Order(1)
	void save_should_return_200_and_code_201() throws Exception {
		String body = """
        {
          "name": "KaraKedi",
          "address": "Adres satırı 123",
          "cityId": "%s",
          "districtId": "%s",
          "neighborhoodId": "%s",
          "ownerId": "%s",
          "phone": "05321234567",
          "website": "https://karakedi.example",
          "description": "Canlı müzik mekânı",
          "musicStartTime": "21:00"
        }
        """.formatted(cityId, districtId, neighborhoodId, ownerId);
		
		mockMvc.perform(post(BASE + SAVE)
				                .contentType(APPLICATION_JSON)
				                .content(body.getBytes(StandardCharsets.UTF_8)))
		       .andExpect(status().isOk())
		       .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201))
		       .andExpect(jsonPath("$.data.id").isNotEmpty())
		       .andExpect(jsonPath("$.data.name").value("KaraKedi"))
		       .andExpect(jsonPath("$.data.cityName").value("Ankara"))
		       .andExpect(jsonPath("$.data.districtName").value("Çankaya"))
		       .andExpect(jsonPath("$.data.neighborhoodName").value("Bahçelievler"))
		       .andExpect(jsonPath("$.data.ownerId").value(ownerId.toString()));
	}
	
	// ---------- GET BY ID ----------
	@Test
	@Order(2)
	void getById_should_return_200() throws Exception {
		UUID createdId = TestUtil.createVenueViaApi(mockMvc, cityId, districtId, neighborhoodId, ownerId);
		
		mockMvc.perform(get(BASE + GET_BY_ID, createdId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.id").value(createdId.toString()))
		       .andExpect(jsonPath("$.data.name").value("TestVenue"))
		       .andExpect(jsonPath("$.data.cityName").value("Ankara"));
	}
	
	// ---------- UPDATE ----------
	@Test
	@Order(3)
	void update_should_return_200_and_reflect_changes() throws Exception {
		UUID createdId = TestUtil.createVenueViaApi(mockMvc, cityId, districtId, neighborhoodId, ownerId);
		
		String updateBody = """
        {
          "name": "GüncelAd",
          "address": "Yeni adres",
          "cityId": "%s",
          "districtId": "%s",
          "neighborhoodId": "%s",
          "ownerId": "%s",
          "phone": "05001112233",
          "website": "https://guncel.example",
          "description": "Güncellendi",
          "musicStartTime": "22:00"
        }
        """.formatted(cityId, districtId, neighborhoodId, ownerId);
		
		mockMvc.perform(put(BASE + UPDATE, createdId)
				                .contentType(APPLICATION_JSON)
				                .content(updateBody))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.id").value(createdId.toString()))
		       .andExpect(jsonPath("$.data.name").value("GüncelAd"))
		       .andExpect(jsonPath("$.data.address").value("Yeni adres"))
		       .andExpect(jsonPath("$.data.phone").value("05001112233"))
		       .andExpect(jsonPath("$.data.website").value("https://guncel.example"))
		       .andExpect(jsonPath("$.data.description").value("Güncellendi"))
		       .andExpect(jsonPath("$.data.musicStartTime").value("22:00"));
	}
	
	// ---------- GET ALL ----------
	@Test
	@Order(4)
	void findAll_should_return_200_and_list() throws Exception {
		TestUtil.createVenueViaApi(mockMvc, cityId, districtId, neighborhoodId, ownerId);
		TestUtil.createVenueViaApi(mockMvc, cityId, districtId, neighborhoodId, ownerId);
		
		mockMvc.perform(get(BASE + GET_ALL))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))));
	}
	
	// ---------- DELETE ----------
	@Test
	@Order(5)
	void delete_should_return_200() throws Exception {
		UUID createdId = TestUtil.createVenueViaApi(mockMvc, cityId, districtId, neighborhoodId, ownerId);
		
		mockMvc.perform(delete(BASE + DELETE, createdId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
	}
	
	// ---------- (DEVRE DIŞI) FORBIDDEN ----------
	@Disabled("Security filtreleri kapalı: @AutoConfigureMockMvc(addFilters=false)")
	@Test
	@Order(6)
	void save_should_forbid_without_permission() throws Exception {
		String body = """
        {
          "name": "Nope",
          "address": "X",
          "cityId": "%s",
          "districtId": "%s",
          "neighborhoodId": "%s",
          "ownerId": "%s"
        }
        """.formatted(cityId, districtId, neighborhoodId, ownerId);
		
		mockMvc.perform(post(BASE + SAVE)
				                .contentType(APPLICATION_JSON)
				                .content(body))
		       .andExpect(status().isForbidden());
	}
	
	// ---- util ----
	static class TestUtil {
		static UUID createVenueViaApi(MockMvc mockMvc, UUID cityId, UUID districtId, UUID neighborhoodId, UUID ownerId) throws Exception {
			String body = """
            {
              "name": "TestVenue",
              "address": "Adres",
              "cityId": "%s",
              "districtId": "%s",
              "neighborhoodId": "%s",
              "ownerId": "%s"
            }
            """.formatted(cityId, districtId, neighborhoodId, ownerId);
			
			var res = mockMvc.perform(post(BASE + SAVE)
					                          .contentType(APPLICATION_JSON)
					                          .content(body))
			                 .andExpect(status().isOk())
			                 .andExpect(jsonPath("$.code").value(201))
			                 .andReturn();
			
			String json = res.getResponse().getContentAsString();
			var node = new ObjectMapper().readTree(json);
			return UUID.fromString(node.get("data").get("id").asText());
		}
	}
}