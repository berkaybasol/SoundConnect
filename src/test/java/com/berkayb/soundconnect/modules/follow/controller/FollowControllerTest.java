package com.berkayb.soundconnect.modules.follow.controller;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.follow.repository.FollowRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Follow.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestMethodOrder(OrderAnnotation.class)
// >>> HER KOŞUDA AYRI H2: başka testlerle çarpışmayı keser
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-follow-ctrl-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
// (opsiyonel) sınıf bitince context’i at → daha da izole
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("web")
class FollowControllerTest {
	
	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired FollowRepository followRepository;
	
	// MailProducerImpl yüzünden gerekecek
	@MockitoBean RabbitTemplate rabbitTemplate;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	private UUID followerId;
	private UUID followingId;
	
	@BeforeEach
	void seed() {
		// child -> parent sırası
		followRepository.deleteAll();
		userRepository.deleteAll();
		
		User follower = userRepository.save(User.builder()
		                                        .username("follower")
		                                        .email("f@x.com")
		                                        .password("{noop}x")
		                                        .roles(new HashSet<>())
		                                        .build());
		followerId = follower.getId();
		
		User following = userRepository.save(User.builder()
		                                         .username("following")
		                                         .email("g@x.com")
		                                         .password("{noop}x")
		                                         .roles(new HashSet<>())
		                                         .build());
		followingId = following.getId();
	}
	
	// ---------- FOLLOW ----------
	@Test @Order(1)
	void follow_should_return_200_and_isFollowing_true() throws Exception {
		String body = """
          { "followerId": "%s", "followingId": "%s" }
        """.formatted(followerId, followingId);
		
		mockMvc.perform(post(BASE + FOLLOW)
				                .contentType(APPLICATION_JSON)
				                .content(body.getBytes(StandardCharsets.UTF_8)))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
		
		mockMvc.perform(get(BASE + IS_FOLLOWING)
				                .param("followerId", followerId.toString())
				                .param("followingId", followingId.toString()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data").value(true));
	}
	
	// ---------- UNFOLLOW ----------
	@Test @Order(2)
	void unfollow_should_return_200_and_isFollowing_false() throws Exception {
		follow(followerId, followingId);
		
		String body = """
          { "followerId": "%s", "followingId": "%s" }
        """.formatted(followerId, followingId);
		
		mockMvc.perform(post(BASE + UNFOLLOW)
				                .contentType(APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
		
		mockMvc.perform(get(BASE + IS_FOLLOWING)
				                .param("followerId", followerId.toString())
				                .param("followingId", followingId.toString()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data").value(false));
	}
	
	// ---------- GET FOLLOWING ----------
	@Test @Order(3)
	void getFollowing_should_list_entries() throws Exception {
		follow(followerId, followingId);
		
		mockMvc.perform(get(BASE + GET_FOLLOWING, followerId))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
		       .andExpect(jsonPath("$.data[0].followerId").value(followerId.toString()))
		       .andExpect(jsonPath("$.data[0].followingId").value(followingId.toString()))
		       .andExpect(jsonPath("$.data[0].followerUsername").value("follower"))
		       .andExpect(jsonPath("$.data[0].followingUsername").value("following"));
	}
	
	// ---------- GET FOLLOWERS ----------
	@Test @Order(4)
	void getFollowers_should_list_entries() throws Exception {
		follow(followerId, followingId);
		
		mockMvc.perform(get(BASE + GET_FOLLOWERS, followingId))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
		       .andExpect(jsonPath("$.data[0].followerId").value(followerId.toString()))
		       .andExpect(jsonPath("$.data[0].followingId").value(followingId.toString()))
		       .andExpect(jsonPath("$.data[0].followerUsername").value("follower"))
		       .andExpect(jsonPath("$.data[0].followingUsername").value("following"));
	}
	
	// ---------- NEGATIVE: SELF FOLLOW ----------
	@Test @Order(5)
	void follow_should_fail_when_self_follow() throws Exception {
		String body = """
          { "followerId": "%s", "followingId": "%s" }
        """.formatted(followerId, followerId);
		
		mockMvc.perform(post(BASE + FOLLOW)
				                .contentType(APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
		       .andExpect(jsonPath("$.message").exists());
	}
	
	// ---------- NEGATIVE: DUPLICATE FOLLOW ----------
	@Test
	void follow_should_fail_when_duplicate() throws Exception {
		String body = """
          {"followerId":"%s","followingId":"%s"}
        """.formatted(followerId, followingId);
		
		mockMvc.perform(post(BASE + FOLLOW)
				                .contentType(APPLICATION_JSON)
				                .content(body))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true));
		
		mockMvc.perform(post(BASE + FOLLOW)
				                .contentType(APPLICATION_JSON)
				                .content(body))
		       .andExpect(status().isConflict())
		       .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
		       .andExpect(jsonPath("$.code").value(1201))
		       .andExpect(jsonPath("$.httpStatus").value("CONFLICT"))
		       .andExpect(jsonPath("$.message").exists());
	}
	
	// ---- util ----
	private void follow(UUID followerId, UUID followingId) throws Exception {
		String body = """
          { "followerId": "%s", "followingId": "%s" }
        """.formatted(followerId, followingId);
		
		mockMvc.perform(post(BASE + FOLLOW)
				                .contentType(APPLICATION_JSON)
				                .content(body))
		       .andExpect(status().isOk());
	}
}