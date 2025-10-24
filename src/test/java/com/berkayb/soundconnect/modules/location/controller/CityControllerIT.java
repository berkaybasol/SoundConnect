// CityControllerIT.java
package com.berkayb.soundconnect.modules.location.controller;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
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

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-city-it-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("web")
class CityControllerIT {
	
	@Autowired MockMvc mockMvc;
	@Autowired CityRepository cityRepository;
	
	// --- AMQP & mail tarafını körleyelim ---
	@MockitoBean private MailProducer mailProducer;
	
	// ❶ Registry’yi mockla ki container yaratmaya kalkışmasın
	@MockitoBean private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;
	
	// ❷ Fabrika bean’ini isimle mockla ve “boş olmayan” bir container döndürmesini stub’la
	@MockitoBean(name = "rabbitListenerContainerFactory")
	private RabbitListenerContainerFactory<?> rabbitFactory;
	
	@MockitoBean RabbitTemplate rabbitTemplate;
	@MockitoBean RedisConnectionFactory redisConnectionFactory;
	@MockitoBean RedisTemplate<String, String> redisTemplate;
	@MockitoBean OtpService otpService;
	@MockitoBean StringRedisTemplate stringRedisTemplate;
	@MockitoBean MailJobHelper mailJobHelper;
	@MockitoBean MailSenderClient mailSenderClient;
	@MockitoBean org.springframework.amqp.support.converter.Jackson2JsonMessageConverter jackson2JsonMessageConverter;
	
	
	// Bazı config'ler CachingConnectionFactory ister
	@MockitoBean(name = "rabbitConnectionFactory")
	org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory;
	
	// İstersen tüketiciyi de körle
	@MockitoBean com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer dlqMailJobConsumer;
	
	private static final String BASE = EndPoints.City.BASE;
	
	@BeforeEach
	void setup() {
		cityRepository.deleteAll();
		
		
	}
	
	@Test
	void save_and_getAll_and_getById_and_delete_flow() throws Exception {
		// create
		mockMvc.perform(post(BASE + EndPoints.City.SAVE)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content("{\"name\":\"Ankara\"}"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.code").value(201))
		       .andExpect(jsonPath("$.data.id", notNullValue()))
		       .andExpect(jsonPath("$.data.name").value("Ankara"));
		
		// list
		mockMvc.perform(get(BASE + EndPoints.City.GET_ALL))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
		
		// get by id
		City city = cityRepository.findAll().get(0);
		mockMvc.perform(get(BASE + EndPoints.City.GET_CITY, city.getId()))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.id").value(city.getId().toString()))
		       .andExpect(jsonPath("$.data.name").value("Ankara"));
		
		// delete
		mockMvc.perform(delete(BASE + EndPoints.City.DELETE, city.getId()))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.code").value(200));
		
		// list after delete
		mockMvc.perform(get(BASE + EndPoints.City.GET_ALL))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.length()", is(0)));
	}
}