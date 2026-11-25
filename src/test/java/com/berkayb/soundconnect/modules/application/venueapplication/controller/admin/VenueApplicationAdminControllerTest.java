package com.berkayb.soundconnect.modules.application.venueapplication.controller.admin;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationDecisionNotifications;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.notification.dlq.NotificationDlqBroker;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.config.ExternalRabbitConsumersTestIsolation;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({ExternalRabbitConsumersTestIsolation.class, VenueApplicationAdminControllerTest.NotificationSchema.class})
class VenueApplicationAdminControllerTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		properties.add("spring.datasource.username", POSTGRES::getUsername);
		properties.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class NotificationSchema {
		@Bean @DependsOn("entityManagerFactory")
		InitializingBean notificationMigrations(JdbcTemplate jdbc) {
			return () -> {
				for (String script : List.of("2026-09-28-media-notification-identity.sql", "2026-09-29-band-notification-identity.sql")) {
					jdbc.execute(Files.readString(Path.of("scripts/db", script)));
				}
			};
		}
	}
	
	private static final String BASE = "/api/v1/admin/venue-applications";
	
	@Autowired MockMvc mockMvc;
	
    @Autowired com.berkayb.soundconnect.modules.role.repository.PermissionRepository permissions;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired VenueApplicationRepository venueApplicationRepository;
	
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	
	@MockitoBean RabbitTemplate rabbitTemplate;
	@MockitoBean NotificationDlqBroker notificationDlqBroker;
	@MockitoBean RedisConnectionFactory redisConnectionFactory;
	@MockitoBean RedisTemplate<String, String> redisTemplate;
	@MockitoBean OtpService otpService;
	
	@MockitoBean StringRedisTemplate stringRedisTemplate;
	@MockitoBean MailJobHelper mailJobHelper;
	@MockitoBean MailSenderClient mailSenderClient;
	@MockitoBean org.springframework.amqp.support.converter.Jackson2JsonMessageConverter jackson2JsonMessageConverter;
	
	// MailQueueConfig rabbitListenerContainerFactory CachingConnectionFactory beklediği için:
	@MockitoBean(name = "rabbitConnectionFactory")
	org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory;
	
	// İstersen tüketiciyi de körleyelim:
	@MockitoBean com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer dlqMailJobConsumer;
	
	// Profil oluşturmayı stub’la
	@MockitoBean VenueProfileService venueProfileService;
	// This fixture exercises the HTTP/domain decision. Native receipt/inbox atomicity is
	// covered by VenueApplicationPushPostgresTest with the real decision producer.
	@MockitoBean VenueApplicationDecisionNotifications decisionNotifications;
	
	private User admin;
	private UserDetailsImpl adminDetails;
	
	private City city;
	private District district;
	private Neighborhood neighborhood;
	
	@BeforeEach
	void setup() {
		// --- temizle (FK sırasına dikkat) ---
		venueApplicationRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		roleRepository.deleteAll();
		
		// --- seed role ---
		roleRepository.save(Role.builder().name(RoleEnum.ROLE_VENUE.name()).build());
		
		// --- seed location ---
		city = cityRepository.save(City.builder().name("AdminCity_" + UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("AdminDistrict").city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("AdminNeighborhood").district(district).build());
		
        var grants = new java.util.HashSet<com.berkayb.soundconnect.modules.role.entity.Permission>();
        for (var name : List.of("MANAGE_VENUE_APPLICATIONS", "ADMIN_PANEL_ACCESS")) {
            grants.add(permissions.findByName(name).orElseGet(() -> permissions.save(
                    com.berkayb.soundconnect.modules.role.entity.Permission.builder().name(name).build())));
        }
		// --- seed admin ---
		admin = userRepository.save(User.builder().status(UserStatus.ACTIVE).permissions(grants)
		                                .username("admin_" + UUID.randomUUID().toString().substring(0, 12))
		                                .email("admin_" + UUID.randomUUID() + "@test.local")
		                                .password("x")
		                                .provider(AuthProvider.LOCAL)
		                                .emailVerified(true)
		                                .city(city)
		                                .build());
		adminDetails = new UserDetailsImpl(admin);
		SecurityContextHolder.getContext().setAuthentication(adminAuth());
		
		// --- venue profile create stub ---
		Mockito.when(venueProfileService.createProfile(
				Mockito.any(UUID.class),
				Mockito.any(VenueProfileSaveRequestDto.class)
		)).thenReturn(null);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}
	
	private UsernamePasswordAuthenticationToken adminAuth() {
		return new UsernamePasswordAuthenticationToken(
				adminDetails, "x", List.of(new SimpleGrantedAuthority("MANAGE_VENUE_APPLICATIONS"), new SimpleGrantedAuthority("ADMIN_PANEL_ACCESS"))
		);
	}
	
	private User seedApplicant() {
		return userRepository.save(User.builder()
		                               .username("applicant_" + UUID.randomUUID().toString().substring(0, 12))
		                               .email("applicant_" + UUID.randomUUID() + "@test.local")
		                               .password("x")
		                               .provider(AuthProvider.LOCAL)
		                               .emailVerified(true)
		                               .status(UserStatus.PENDING_VENUE_REQUEST)
		                               .city(city)
		                               .phone("05551234567")
		                               .build());
	}
	
	private VenueApplication seedApp(User applicant, ApplicationStatus status) {
		return venueApplicationRepository.save(VenueApplication.builder()
		                                                       .applicant(applicant)
		                                                       .venueName("My Venue " + UUID.randomUUID())
		                                                       .venueAddress("Addr 123")
		                                                       .phone(applicant.getPhone())
		                                                       .status(status)
		                                                       .applicationDate(LocalDateTime.now())
		                                                       .decisionDate(null)
		                                                       .city(city)
		                                                       .district(district)
		                                                       .neighborhood(neighborhood)
		                                                       .build());
	}
	
	@Test
	void approve_ok() throws Exception {
		var applicant = seedApplicant();
		var app = seedApp(applicant, ApplicationStatus.PENDING);
		expectDecisionAtInvocation(app.getId(), applicant.getId(), ApplicationStatus.APPROVED);
		
		mockMvc.perform(post(BASE + "/approve/{id}", app.getId())
				                .with(authentication(adminAuth())))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.status").value("APPROVED"))
		       .andExpect(jsonPath("$.data.id").value(app.getId().toString()));
		
		// yan etkiler
		var refreshed = userRepository.findById(applicant.getId()).orElseThrow();
		boolean hasVenueRole = refreshed.getRoles().stream()
		                                .anyMatch(r -> r.getName().equals(RoleEnum.ROLE_VENUE.name()));
		assertThat(hasVenueRole).isTrue();
		
		List<Venue> venues = venueRepository.findAll();
		assertThat(venues).isNotEmpty();
		assertThat(venues.get(0).getOwner().getId()).isEqualTo(applicant.getId());
		Mockito.verify(decisionNotifications).decided(Mockito.argThat(decision ->
				decision.getId().equals(app.getId())
						&& decision.getApplicant().getId().equals(applicant.getId())
						&& decision.getStatus() == ApplicationStatus.APPROVED
						&& decision.getDecisionDate() != null));
	}
	
	@Test
	void approve_should_fail_when_not_pending() throws Exception {
		var applicant = seedApplicant();
		var app = seedApp(applicant, ApplicationStatus.REJECTED); // ya da APPROVED
		
		mockMvc.perform(post(BASE + "/approve/{id}", app.getId())
				                .with(authentication(adminAuth())))
		       .andDo(print())
		       .andExpect(status().isConflict())
		       .andExpect(jsonPath("$.message", containsString("Invalid")));
	}
	
    @Test
    void revokingPanelGrantAfterAuthenticationBlocksBothDecisions() throws Exception {
        var app = seedApp(seedApplicant(), ApplicationStatus.PENDING);
        var stored = userRepository.findById(admin.getId()).orElseThrow();
        stored.getPermissions().removeIf(p -> "ADMIN_PANEL_ACCESS".equals(p.getName()));
        userRepository.saveAndFlush(stored);
        mockMvc.perform(get(BASE + "/{id}", app.getId()).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/by-status").param("status", "PENDING").with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/approve/{id}", app.getId()).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/reject/{id}", app.getId()).contentType("application/json")
                        .content("{\"reason\":\"Adres eksik\"}").with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
        var unchanged = venueApplicationRepository.findById(app.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(unchanged.getReviewedById()).isNull();
        assertThat(unchanged.getRejectionReason()).isNull();
        Mockito.verifyNoInteractions(decisionNotifications);
    }

	@Test
	void reject_ok() throws Exception {
		var applicant = seedApplicant();
		var app = seedApp(applicant, ApplicationStatus.PENDING);
		expectDecisionAtInvocation(app.getId(), applicant.getId(), ApplicationStatus.REJECTED);
		
		mockMvc.perform(post(BASE + "/reject/{id}", app.getId())
				                .param("reason", "Eksik bilgi")
				                .with(authentication(adminAuth())))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.status").value("REJECTED"));
		Mockito.verify(decisionNotifications).decided(Mockito.argThat(decision ->
				decision.getId().equals(app.getId())
						&& decision.getApplicant().getId().equals(applicant.getId())
						&& decision.getStatus() == ApplicationStatus.REJECTED
						&& decision.getDecisionDate() != null));
	}

	private void expectDecisionAtInvocation(UUID applicationId, UUID recipientId, ApplicationStatus status) {
		Mockito.doAnswer(invocation -> {
			VenueApplication decision = invocation.getArgument(0);
			// Check now: a later mutation of the same entity must not repair an early call.
			assertThat(decision.getId()).isEqualTo(applicationId);
			assertThat(decision.getApplicant().getId()).isEqualTo(recipientId);
			assertThat(decision.getStatus()).isEqualTo(status);
			assertThat(decision.getDecisionDate()).isNotNull();
			if (status == ApplicationStatus.APPROVED) {
				assertThat(decision.getApprovedVenue()).isNotNull();
				assertThat(decision.getApprovedVenue().getId()).isNotNull();
				assertThat(decision.getApprovedVenue().getOwner().getId()).isEqualTo(recipientId);
			} else {
				assertThat(decision.getApprovedVenue()).isNull();
			}
			return null;
		}).when(decisionNotifications).decided(Mockito.any(VenueApplication.class));
	}
	
	@Test
	void get_by_status_ok() throws Exception {
		var a1 = seedApplicant();
		var a2 = seedApplicant();
		var a3 = seedApplicant();
		
		seedApp(a1, ApplicationStatus.PENDING);
		seedApp(a2, ApplicationStatus.PENDING);
		seedApp(a3, ApplicationStatus.APPROVED);
		
		mockMvc.perform(get(BASE + "/by-status").param("status", "PENDING")
				                .with(authentication(adminAuth())))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(2)));
	}
	
	@Test
	void get_by_id_ok() throws Exception {
		var applicant = seedApplicant();
		var app = seedApp(applicant, ApplicationStatus.PENDING);
		
		mockMvc.perform(get(BASE + "/{id}", app.getId())
				                .with(authentication(adminAuth())))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.id").value(app.getId().toString()))
		       .andExpect(jsonPath("$.data.applicantUsername").value(applicant.getUsername()));
	}
}
