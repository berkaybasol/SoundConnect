package com.berkayb.soundconnect.modules.user.controller;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.mail.MailProducer; // Auth → Mail gönderen boundary arayüzü

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean; // ✅ Spring 6.2 / Boot 3.4 önerilen mock anotasyonu
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AMAÇ
 * =====
 * Bu test, gerçek bir HTTP çağrısı ile aşağıdaki zinciri uçtan uca doğrular:
 *   Controller → Service → Repository → H2 (test DB) → Mapper → JSON response
 *
 * NEDEN @MockitoBean?
 * ===================
 * Uygulama context’i ayağa kalkarken Auth tarafında MailProducerImpl, constructor’da RabbitTemplate ister.
 * Test profilinde Rabbit otokonfigürasyonunu kapattığımız için RabbitTemplate bean’i yok ve context patlar.
 * Bunu önlemek için "boundary" arayüz olan MailProducer’ı testte mock’luyoruz.
 * Böylece alttaki RabbitTemplate bağımlılığı hiç oluşturulmadan context sağlıklı kurulur.
 *
 * GÜVENLİK
 * ========
 * @AutoConfigureMockMvc(addFilters = false) ile güvenlik filtrelerini test kapsamı dışında bırakıyoruz.
 * Yani endpoint’e auth’suz istek atabiliyoruz.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test") // src/test/resources/application-test.yml devrede
@WithMockUser(username = "admin", authorities = {"ADMIN:GET_ALL_USERS"}) // kendi authority stringin neyse
@Tag("web")
class UserControllerIT {
	
	@Autowired
	private MockMvc mockMvc; // HTTP isteği atmak için
	
	@Autowired
	private UserRepository userRepository; // Test datasını H2'ye basmak için
	
	// ✅ Yeni test anotasyonu: Spring Framework 6.2 ile gelen bean-override mekanizması
	// Bu mock, gerçek MailProducerImpl yerine enjekte edilir; böylece RabbitTemplate ihtiyacı ortadan kalkar.
	@MockitoBean
	private MailProducer mailProducer;
	
	@MockitoBean
	private RabbitTemplate rabbitTemplate;
	
	@MockitoBean(name = "rabbitListenerContainerFactory")   // ⚠️ isim birebir böyle olmalı
	private RabbitListenerContainerFactory<?> rabbitFactory;
	
	// Endpoints tek kaynaktan (hardcode string yok)
	private static final String GET_ALL_URL = EndPoints.User.BASE + EndPoints.User.GET_ALL; // "/api/v1/users/get-all-users"
	
	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		// opsiyonel ama iyi olur:
		// entityManager.flush();
		// entityManager.clear();
		
		User u1 = User.builder()
		              // .id(UUID.randomUUID())  <-- KALDIR
		              .username("berkay")
		              .password("hash")
		              .email("berkay@soundconnect.app")
		              .gender(Gender.MALE)
		              .status(UserStatus.ACTIVE)
		              .build();
		
		User u2 = User.builder()
		              // .id(UUID.randomUUID())  <-- KALDIR
		              .username("ahmet")
		              .password("hash")
		              .email("ahmet@soundconnect.app")
		              .gender(Gender.MALE)
		              .status(UserStatus.ACTIVE)
		              .build();
		
		userRepository.save(u1);
		userRepository.save(u2);
	}
	
	
	/**
	 * NEYİ DOĞRULUYORUZ?
	 * -------------------
	 * - GET /api/v1/users/get-all-users çağrısı 200 OK dönmeli
	 * - Response JSON bir dizi olmalı ve en az 2 eleman içermeli (seed ettiklerimiz)
	 * - İlk elemanın 'username' alanı boş olmamalı
	 * - 'gender' alanı beklenenlerden biri olmalı (mapper null döndürebilir; o yüzden nullValue da kabul)
	 *
	 * İPUCU:
	 * Mapper "roles" veya "emailVerified" gibi alanları garanti set ediyorsa, aşağıya ilave assert'ler ekleyebilirsin.
	 */
	@Test
	void getAllUsers_ShouldReturnOkAndList() throws Exception {
		mockMvc.perform(get(GET_ALL_URL).accept(MediaType.APPLICATION_JSON))
		       .andExpect(status().isOk())
		       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
		       // wrapper alanları
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       // asıl liste $.data altında
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(2)))
		       .andExpect(jsonPath("$.data[0].username", not(blankOrNullString())))
		       .andExpect(jsonPath("$.data[0].gender",
		                           anyOf(is("MALE"), is("FEMALE"), is(nullValue()))));
	}
}