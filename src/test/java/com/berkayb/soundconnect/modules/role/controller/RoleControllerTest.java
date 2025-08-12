package com.berkayb.soundconnect.modules.role.controller;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.mapper.RoleMapper;
import com.berkayb.soundconnect.modules.role.repository.PermissionRepository;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Role.*;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false) // security filter'ları kapalı
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestMethodOrder(OrderAnnotation.class)
// method-level @PreAuthorize için sahte kullanıcı
@WithMockUser(username = "test-admin",
		authorities = {"READ_ROLE","WRITE_ROLE","DELETE_ROLE"})
class RoleControllerTest {
	
	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired PermissionRepository permissionRepository;
	
	// MailProducerImpl için gerekli
	@MockitoBean
	RabbitTemplate rabbitTemplate;
	
	// MapStruct güvence amaçlı
	@MockitoSpyBean
	RoleMapper roleMapper;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private UUID permAId;
	private UUID permBId;
	
	@BeforeEach
	void setup() {
		// child -> parent sırası
		roleRepository.deleteAll();
		permissionRepository.deleteAll();
		
		// iki permission seed
		Permission pA = permissionRepository.save(Permission.builder()
		                                                    .name("READ_SOMETHING_" + UUID.randomUUID())
		                                                    .build());
		Permission pB = permissionRepository.save(Permission.builder()
		                                                    .name("WRITE_SOMETHING_" + UUID.randomUUID())
		                                                    .build());
		
		permAId = pA.getId();
		permBId = pB.getId();
	}
	
	// ---------- CREATE ----------
	@Test
	@Order(1)
	void save_should_return_200_and_code_201() throws Exception {
		String roleName = "ROLE_TEST_" + UUID.randomUUID();
		
		String body = """
        {
          "name": "%s",
          "permissionIds": ["%s", "%s"]
        }
        """.formatted(roleName, permAId, permBId);
		
		mockMvc.perform(put(BASE + SAVE)
				                .contentType(APPLICATION_JSON)
				                .content(body.getBytes(StandardCharsets.UTF_8)))
		       .andDo(print())
		       .andExpect(status().isOk())                 // HTTP 200
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201))   // body.code 201
		       .andExpect(jsonPath("$.data.id").isNotEmpty())
		       .andExpect(jsonPath("$.data.name").value(roleName))
		       .andExpect(jsonPath("$.data.permissions", org.hamcrest.Matchers.hasSize(2)));
	}
	
	// ---------- LIST ----------
	@Test
	@Order(2)
	void getAll_should_return_200_and_list() throws Exception {
		// seed iki rol (repo üzerinden)
		Permission pA = permissionRepository.findById(permAId).orElseThrow();
		Permission pB = permissionRepository.findById(permBId).orElseThrow();
		
		roleRepository.save(Role.builder()
		                        .name("ROLE_ALPHA_" + UUID.randomUUID())
		                        .permissions(Set.of(pA))
		                        .build());
		
		roleRepository.save(Role.builder()
		                        .name("ROLE_BETA_" + UUID.randomUUID())
		                        .permissions(Set.of(pB))
		                        .build());
		
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
		// önce API ile bir rol oluştur
		UUID createdRoleId = createRoleViaApi("ROLE_DEL_" + UUID.randomUUID(), List.of(permAId));
		
		// sonra sil
		mockMvc.perform(delete(BASE + DELETE, createdRoleId))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
	}
	
	// ---------- NEGATIVE: DUPLICATE ----------
	@Test
	@Order(4)
	void save_should_fail_when_duplicate_name() throws Exception {
		String duplicateName = "ROLE_DUP_" + UUID.randomUUID();
		
		// 1) başarılı
		createRoleViaApi(duplicateName, List.of(permAId, permBId));
		
		// 2) aynı isim -> 400 + ErrorResponse (GlobalExceptionHandler)
		String body = """
        { "name": "%s", "permissionIds": ["%s"] }
        """.formatted(duplicateName, permAId);
		
		mockMvc.perform(put(BASE + SAVE)
				                .contentType(APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isBadRequest())
		       .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
		       .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
		       .andExpect(jsonPath("$.message").value("Role already exists"));
		// Error code'u sabit bilmiyorsak spesifik sayıya bağlamıyoruz.
	}
	
	// ---- util ----
	private UUID createRoleViaApi(String name, List<UUID> permissionIds) throws Exception {
		String ids = permissionIds.stream()
		                          .map(UUID::toString)
		                          .map(s -> "\"" + s + "\"")
		                          .reduce((a, b) -> a + "," + b)
		                          .orElse("");
		
		String body = """
        {
          "name": "%s",
          "permissionIds": [%s]
        }
        """.formatted(name, ids);
		
		var res = mockMvc.perform(put(BASE + SAVE)
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