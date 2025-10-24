package com.berkayb.soundconnect.modules.location.controller;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-district-it-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
class DistrictControllerIT {
	
	@Autowired MockMvc mockMvc;
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	
	// --- AMQP/Rabbit tarafını tamamen no-op yap ---
	@MockitoBean RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;
	@MockitoBean org.springframework.amqp.support.converter.Jackson2JsonMessageConverter jackson2JsonMessageConverter;
	@MockitoBean(name = "rabbitConnectionFactory")
	org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory;
	
	// --- App’in başka yerlerde beklediği yardımcı bağımlılıklar ---
	@MockitoBean RedisConnectionFactory redisConnectionFactory;
	@MockitoBean RedisTemplate<String, String> redisTemplate;
	@MockitoBean StringRedisTemplate stringRedisTemplate;
	@MockitoBean OtpService otpService;
	@MockitoBean MailJobHelper mailJobHelper;
	@MockitoBean MailSenderClient mailSenderClient;
	
	private City city;
	
	private static final String BASE = EndPoints.District.BASE;             // "/api/v1/districts"
	private static final String SAVE = BASE + EndPoints.District.SAVE;      // "/save-district"
	private static final String GET_ALL = BASE + EndPoints.District.GET_ALL;
	private static final String GET_BY_ID = BASE + EndPoints.District.GET_BY_ID;       // "/get-by-id/{id}"
	private static final String GET_BY_CITY = BASE + EndPoints.District.GET_BY_CITY;   // "/get-by-city/{cityId}"
	private static final String DELETE = BASE + EndPoints.District.DELETE;             // "/delete-district/{id}"
	
	@BeforeEach
	void setup() {
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		city = cityRepository.save(City.builder().name("TestCity").build());
	}
	
	@Test
	void save_and_getAll_and_getById_and_getByCity_and_delete_flow() throws Exception {
		// --- SAVE ---
		String payload = """
            {"name":"Merkez","cityId":"%s"}
        """.formatted(city.getId());
		
		mockMvc.perform(post(SAVE)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(payload))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201))
		       .andExpect(jsonPath("$.data.id", not(blankOrNullString())))
		       .andExpect(jsonPath("$.data.name").value("Merkez"))
		       .andExpect(jsonPath("$.data.cityId").value(city.getId().toString()));
		
		// --- GET ALL ---
		mockMvc.perform(get(GET_ALL).accept(MediaType.APPLICATION_JSON))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
		
		// District id’yi repo’dan çek
		String firstId = districtRepository.findAll().getFirst().getId().toString();
		
		// --- GET BY ID ---
		mockMvc.perform(get(GET_BY_ID.replace("{id}", firstId)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.id").value(firstId))
		       .andExpect(jsonPath("$.data.cityId").value(city.getId().toString()));
		
		// --- GET BY CITY ---
		mockMvc.perform(get(GET_BY_CITY.replace("{cityId}", city.getId().toString())))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
		
		// --- DELETE ---
		mockMvc.perform(delete(DELETE.replace("{id}", firstId)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
		
		// --- After delete ---
		mockMvc.perform(get(GET_BY_CITY.replace("{cityId}", city.getId().toString())))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data", hasSize(0)));
	}
}