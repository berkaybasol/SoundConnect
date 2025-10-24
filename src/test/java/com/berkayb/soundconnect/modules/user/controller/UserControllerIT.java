package com.berkayb.soundconnect.modules.user.controller;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@WithMockUser(username = "admin", authorities = {"ADMIN:GET_ALL_USERS"})
@Tag("web")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-user-it-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
// 🧪 Bu import ile aşağıdaki DummyRabbitFactory test context'e bean olarak girer
@Import(UserControllerIT.DummyRabbitFactoryConfig.class)
class UserControllerIT {
	
	@Autowired private MockMvc mockMvc;
	@Autowired private UserRepository userRepository;
	
	// --- Altyapı bağımlılıkları mock ---
	@MockitoBean private MailProducer mailProducer;
	@MockitoBean RedisConnectionFactory redisConnectionFactory;
	@MockitoBean RedisTemplate<String, String> redisTemplate;
	@MockitoBean StringRedisTemplate stringRedisTemplate;
	@MockitoBean OtpService otpService;
	@MockitoBean MailJobHelper mailJobHelper;
	@MockitoBean MailSenderClient mailSenderClient;
	
	@MockitoBean
	org.springframework.amqp.support.converter.Jackson2JsonMessageConverter jackson2JsonMessageConverter;
	
	@MockitoBean(name = "rabbitConnectionFactory")
	org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory;
	
	@MockitoBean
	com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer dlqMailJobConsumer;
	
	private static final String GET_ALL_URL = EndPoints.User.BASE + EndPoints.User.GET_ALL;
	
	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		
		userRepository.save(User.builder()
		                        .username("berkay")
		                        .password("hash")
		                        .email("berkay@soundconnect.app")
		                        .gender(Gender.MALE)
		                        .status(UserStatus.ACTIVE)
		                        .build());
		
		userRepository.save(User.builder()
		                        .username("ahmet")
		                        .password("hash")
		                        .email("ahmet@soundconnect.app")
		                        .gender(Gender.MALE)
		                        .status(UserStatus.ACTIVE)
		                        .build());
	}
	
	@Test
	void getAllUsers_ShouldReturnOkAndList() throws Exception {
		mockMvc.perform(get(GET_ALL_URL).accept(MediaType.APPLICATION_JSON))
		       .andExpect(status().isOk())
		       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(2)))
		       .andExpect(jsonPath("$.data[0].username", not(blankOrNullString())))
		       .andExpect(jsonPath("$.data[0].gender",
		                           anyOf(is("MALE"), is("FEMALE"), is(nullValue()))));
	}
	
	/**
	 * 🔧 Context boot ESNASINDA devreye giren, null dönmeyen sahte bir RabbitListenerContainerFactory.
	 * Böylece RabbitListenerAnnotationBeanPostProcessor container oluştururken NPE yemez.
	 */
	@TestConfiguration
	static class DummyRabbitFactoryConfig {
		@Bean(name = "rabbitListenerContainerFactory")
		RabbitListenerContainerFactory<?> rabbitListenerContainerFactory() {
			return new RabbitListenerContainerFactory<>() {
				@Override
				public MessageListenerContainer createListenerContainer(RabbitListenerEndpoint endpoint) {
					// Tüm void çağrıları yutan “no-op” bir mock
					return Mockito.mock(MessageListenerContainer.class);
				}
			};
		}
	}
}