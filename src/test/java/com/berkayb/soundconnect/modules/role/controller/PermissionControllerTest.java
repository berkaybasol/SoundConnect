package com.berkayb.soundconnect.modules.role.controller;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.mapper.PermissionMapper;
import com.berkayb.soundconnect.modules.role.repository.PermissionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Permission.*;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false) // security filtrelerini kapat
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestMethodOrder(OrderAnnotation.class)
class PermissionControllerTest {
	
	@Autowired MockMvc mockMvc;
	@Autowired PermissionRepository permissionRepository;
	
	// Rabbit lazım: MailProducerImpl için mock
	@MockitoBean
	RabbitTemplate rabbitTemplate;
	
	// MapStruct bean’i garanti olsun diye spy’lıyoruz
	@MockitoSpyBean
	PermissionMapper permissionMapper;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	@BeforeEach
	void clean() {
		permissionRepository.deleteAll();
	}
	
	// ---------- CREATE ----------
	@Test
	@Order(1)
	void save_should_return_200_and_body() throws Exception {
		String uniqueName = "TEST_PERMISSION_" + UUID.randomUUID();
		
		String body = """
        { "name": "%s" }
        """.formatted(uniqueName);
		
		mockMvc.perform(post(BASE + SAVE)
				                .contentType(APPLICATION_JSON)
				                .content(body.getBytes(StandardCharsets.UTF_8)))
		       .andDo(print()) // patlarsa sebebi burada görünür
		       .andExpect(status().isOk())
		       .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       // implementasyon 200 gönderiyor
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.id").isNotEmpty())
		       .andExpect(jsonPath("$.data.name").value(uniqueName));
	}
	
	// ---------- LIST ----------
	@Test
	@Order(2)
	void getAll_should_return_200_and_list() throws Exception {
		// seed iki kayıt
		permissionRepository.save(Permission.builder().name("READ_X_" + UUID.randomUUID()).build());
		permissionRepository.save(Permission.builder().name("WRITE_X_" + UUID.randomUUID()).build());
		
		mockMvc.perform(get(BASE + GET_ALL))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(greaterThanOrEqualTo(2))));
	}
	
	// ---------- DELETE ----------
	@Test
	@Order(3)
	void delete_should_return_200() throws Exception {
		String name = "DEL_PERMISSION_" + UUID.randomUUID();
		var createdId = createPermissionViaApi(name);
		
		mockMvc.perform(delete(BASE + DELETE, createdId))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
	}
	
	// ---------- NEGATIVE: DUPLICATE ----------
	@Test
	@Order(4)
	void save_should_fail_when_duplicate_name() throws Exception {
		String name = "DUP_" + UUID.randomUUID();
		
		// 1) ilk istek: başarılı
		createPermissionViaApi(name);
		
		// 2) aynı isim → 400 BAD_REQUEST + ErrorResponse
		String body = """
    { "name": "%s" }
    """.formatted(name);
		
		mockMvc.perform(post(BASE + SAVE)
				                .contentType(APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isBadRequest())
		       .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
		       .andExpect(jsonPath("$.code").value(5004)) // ErrorType.PERMISSION_ALREADY_EXISTS kodun buysa
		       .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
		       .andExpect(jsonPath("$.message").value("Permission already exists"))
		       // Lokalize detay da dönüyor:
		       .andExpect(jsonPath("$.details[0]").value("Bu izin zaten mevcut."));
	}
	
	// ---- util ----
	private UUID createPermissionViaApi(String name) throws Exception {
		String body = """
        { "name": "%s" }
        """.formatted(name);
		
		var res = mockMvc.perform(post(BASE + SAVE)
				                          .contentType(APPLICATION_JSON)
				                          .content(body))
		                 .andDo(print())
		                 .andExpect(status().isOk())
		                 .andExpect(jsonPath("$.data.id").exists())
		                 .andReturn();
		
		String json = res.getResponse().getContentAsString();
		JsonNode node = mapper.readTree(json);
		return UUID.fromString(node.get("data").get("id").asText());
	}
}