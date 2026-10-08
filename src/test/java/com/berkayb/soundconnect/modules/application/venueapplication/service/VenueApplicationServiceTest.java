package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.shared.config.ExternalRabbitConsumersTestIsolation;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = {SoundConnectApplication.class, VenueApplicationServiceTest.NotificationIdentityFixture.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import="})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@Import(ExternalRabbitConsumersTestIsolation.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VenueApplicationServiceTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("venue_application_service_test").withUsername("fixture").withPassword("fixture")
			.withLabel("soundconnect.task", "venue-application-service-test").withReuse(false);

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		properties.add("spring.datasource.username", POSTGRES::getUsername);
		properties.add("spring.datasource.password", POSTGRES::getPassword);
		properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
		properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class NotificationIdentityFixture {
		@Bean @DependsOn("entityManagerFactory")
		InitializingBean notificationIdentityMigrations(JdbcTemplate jdbc) {
			return () -> {
				try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
					assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
				}
				for (String file : List.of("2026-09-28-media-notification-identity.sql",
						"2026-09-29-band-notification-identity.sql", "2026-10-06-application-mail-intents.sql")) {
					jdbc.execute(Files.readString(Path.of("scripts/db", file)));
				}
			};
		}
	}
	
	@Autowired VenueApplicationService venueApplicationService;
	@Autowired VenueApplicationRepository venueAppRepo;
	@Autowired UserRepository userRepo;
	@Autowired CityRepository cityRepo;
	@Autowired DistrictRepository districtRepo;
	@Autowired NeighborhoodRepository neighborhoodRepo;
	@Autowired RoleRepository roleRepo;
	@Autowired VenueRepository venueRepo;
	@Autowired VenueProfileRepository venueProfileRepo;
	@Autowired NotificationRepository notifications;
	@Autowired NotificationReceiptRepository receipts;
	@Autowired PlatformTransactionManager transactionManager;
	
	// Rabbit ihtiyacı olan bean’ler için
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
	@MockitoBean(enforceOverride=true)
	com.berkayb.soundconnect.modules.notification.dlq.NotificationDlqBroker dlqBroker;
	
	
	// İstersen tüketiciyi de körle (gerekmeden geçmesi lazım ama garanti):
	@MockitoBean
	com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer dlqMailJobConsumer;
	
	private City city;
	private District district;
	private Neighborhood neighborhood;
	private User applicant;
	private Role venueRole;
	
	@BeforeEach
	void setUp() {
		// FK sırasına dikkat ederek temizlik
		notifications.deleteAll();
		receipts.deleteAll();
		venueProfileRepo.deleteAll();
		venueAppRepo.deleteAll();
		venueRepo.deleteAll();
		userRepo.deleteAll();
		roleRepo.deleteAll();
		neighborhoodRepo.deleteAll();
		districtRepo.deleteAll();
		cityRepo.deleteAll();
		
		// seed location
		city = cityRepo.save(City.builder().name("TCity_" + UUID.randomUUID()).build());
		district = districtRepo.save(District.builder().name("TDistrict").city(city).build());
		neighborhood = neighborhoodRepo.save(Neighborhood.builder().name("TNeighborhood").district(district).build());
		
		// seed role
		venueRole = roleRepo.save(Role.builder().name(RoleEnum.ROLE_VENUE.name()).build());
		
		// seed user (başvuru sahibi)
		applicant = userRepo.save(User.builder()
		                              .username("user_" + UUID.randomUUID().toString().substring(0, 12))
		                              .email("test+" + UUID.randomUUID() + "@mail.test") // -> eklendi
		                              .password("pwd")
		                              .provider(AuthProvider.LOCAL)
		                              .emailVerified(true)
		                              .status(UserStatus.PENDING_VENUE_REQUEST)
		                              .phone("5551112233")
		                              .city(city)
		                              .build());
	}
	
	private VenueApplicationCreateRequestDto req() {
		return new VenueApplicationCreateRequestDto(
				"Cool Venue",
				"Some Address 123",
				"05551234567",
				city.getId().toString(),
				district.getId().toString(),
				neighborhood.getId() != null ? neighborhood.getId().toString() : null
		);
	}
	
	@Test
	void createApplication_ok() {
		// when
		VenueApplicationResponseDto dto = venueApplicationService.createApplication(applicant.getId(), req());
		
		// then
		assertThat(dto).isNotNull();
		assertThat(dto.status()).isEqualTo(ApplicationStatus.PENDING);
		assertThat(dto.venueName()).isEqualTo("cool venue");
		assertThat(dto.venueAddress()).isEqualTo("Some Address 123");
		assertThat(dto.applicantUsername()).isEqualTo(applicant.getUsername());
		
		List<VenueApplication> all = venueAppRepo.findAll();
		assertThat(all).hasSize(1);
		assertThat(all.get(0).getStatus()).isEqualTo(ApplicationStatus.PENDING);
	}
	
	@Test
	void createApplication_duplicatePending_should_throw() {
		// given: ilk kayıt
		venueApplicationService.createApplication(applicant.getId(), req());
		
		// when/then: ikincisi aynı kullanıcıda pending varken patlamalı
		assertThatThrownBy(() -> venueApplicationService.createApplication(applicant.getId(), req()))
				.isInstanceOf(SoundConnectException.class);
	}

	@Test
	void createApplication_withoutNeighborhood_shouldRejectBeforePersistence() {
		VenueApplicationCreateRequestDto request = new VenueApplicationCreateRequestDto(
				"Cool Venue",
				"Some Address 123",
				"05551234567",
				city.getId().toString(),
				district.getId().toString(),
				null
		);

		assertThatThrownBy(() -> venueApplicationService.createApplication(applicant.getId(), request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		assertThat(venueAppRepo.findAll()).isEmpty();
	}

	@Test
	void createApplication_withNeighborhoodFromAnotherDistrict_shouldReject() {
		District anotherDistrict = districtRepo.save(
				District.builder().name("OtherDistrict").city(city).build()
		);
		Neighborhood anotherNeighborhood = neighborhoodRepo.save(
				Neighborhood.builder().name("OtherNeighborhood").district(anotherDistrict).build()
		);
		VenueApplicationCreateRequestDto request = new VenueApplicationCreateRequestDto(
				"Cool Venue",
				"Some Address 123",
				"05551234567",
				city.getId().toString(),
				district.getId().toString(),
				anotherNeighborhood.getId().toString()
		);

		assertThatThrownBy(() -> venueApplicationService.createApplication(applicant.getId(), request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH));
		assertThat(venueAppRepo.findAll()).isEmpty();
	}
	
	@Test
	void approveApplication_happyPath_createsVenue_assignsRole_and_updatesStatus() {
		// given: pending başvuru
		VenueApplicationResponseDto created = venueApplicationService.createApplication(applicant.getId(), req());
		UUID appId = created.id();
		
		// when
		VenueApplicationResponseDto approved = venueApplicationService.approveApplication(appId, UUID.randomUUID());
		
		// then: application
		assertThat(approved.status()).isEqualTo(ApplicationStatus.APPROVED);
		VenueApplication appEntity = venueAppRepo.findById(appId).orElseThrow();
		assertThat(appEntity.getDecisionDate()).isNotNull();
		
		// then: user role & status
		User refreshed = userRepo.findById(applicant.getId()).orElseThrow();
		boolean hasVenueRole = refreshed.getRoles().stream()
		                                .anyMatch(r -> r.getName().equals(RoleEnum.ROLE_VENUE.name()));
		assertThat(hasVenueRole).isTrue();
		assertThat(refreshed.getStatus()).isEqualTo(UserStatus.ACTIVE);
		
		// then: venue & profile
		List<Venue> venues = venueRepo.findAllByOwnerId(applicant.getId());
		assertThat(venues).hasSize(1);
		Venue v = venues.get(0);
		assertThat(v.getName()).isEqualTo("cool venue");
		assertThat(v.getAddress()).isEqualTo("Some Address 123");
		assertThat(v.getPhone()).isEqualTo("05551234567");
		assertThat(v.getCity().getId()).isEqualTo(city.getId());
		assertThat(v.getDistrict().getId()).isEqualTo(district.getId());
		if (neighborhood.getId() != null) {
			assertThat(v.getNeighborhood().getId()).isEqualTo(neighborhood.getId());
		}
		// profil otomatik yaratıldı mı?
		assertThat(venueProfileRepo.findByVenueId(v.getId())).isPresent();
		assertDecisionNotification(appId, ApplicationStatus.APPROVED);
	}
	
	@Test
	void approveApplication_whenNotPending_should_throw() {
		// given: oluştur + statüyü el ile APPROVED yap
		VenueApplicationResponseDto created = venueApplicationService.createApplication(applicant.getId(), req());
		VenueApplication entity = venueAppRepo.findById(created.id()).orElseThrow();
		entity.setStatus(ApplicationStatus.APPROVED);
		venueAppRepo.save(entity);
		
		// when/then
		assertThatThrownBy(() -> venueApplicationService.approveApplication(created.id(), UUID.randomUUID()))
				.isInstanceOf(SoundConnectException.class);
		assertThat(notifications.count()).isZero();
		assertThat(receipts.count()).isZero();
	}
	
	@Test
	void rejectApplication_happyPath() {
		// given
		VenueApplicationResponseDto created = venueApplicationService.createApplication(applicant.getId(), req());
		
		// when
		VenueApplicationResponseDto rejected = venueApplicationService.rejectApplication(created.id(), UUID.randomUUID(), "yetersiz bilgi");
		
		// then
		assertThat(rejected.status()).isEqualTo(ApplicationStatus.REJECTED);
		VenueApplication app = venueAppRepo.findById(created.id()).orElseThrow();
		assertThat(app.getDecisionDate()).isNotNull();
		
		// mekan oluşmamalı
		assertThat(venueRepo.findAllByOwnerId(applicant.getId())).isEmpty();
		
		// role atanmamış olmalı
		User refreshed = userRepo.findById(applicant.getId()).orElseThrow();
		boolean hasVenueRole = refreshed.getRoles().stream()
		                                .anyMatch(r -> r.getName().equals(RoleEnum.ROLE_VENUE.name()));
		assertThat(hasVenueRole).isFalse();
		assertDecisionNotification(created.id(), ApplicationStatus.REJECTED);
	}

	@Test
	void laterFailureRollsBackActualApprovalDomainInboxAndReceiptTogether() {
		UUID applicationId = venueApplicationService.createApplication(applicant.getId(), req()).id();
		var outerTransaction = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> outerTransaction.executeWithoutResult(status -> {
			var approved = venueApplicationService.approveApplication(applicationId, UUID.randomUUID());
			assertThat(approved.status()).isEqualTo(ApplicationStatus.APPROVED);
			var application = venueAppRepo.findById(applicationId).orElseThrow();
			assertThat(application.getApprovedVenue()).isNotNull();
			assertThat(venueProfileRepo.findByVenueId(application.getApprovedVenue().getId())).isPresent();
			var activated = userRepo.findById(applicant.getId()).orElseThrow();
			assertThat(activated.getStatus()).isEqualTo(UserStatus.ACTIVE);
			assertThat(activated.getRoles()).anyMatch(role -> role.getName().equals(RoleEnum.ROLE_VENUE.name()));
			// Preconditions prove real receipt/inbox writes happened before the outer failure.
			assertDecisionNotification(applicationId, ApplicationStatus.APPROVED);
			throw new IllegalStateException("after real decision notification persistence");
		})).isInstanceOf(IllegalStateException.class)
				.hasMessage("after real decision notification persistence");

		var pending = venueAppRepo.findById(applicationId).orElseThrow();
		assertThat(pending.getStatus()).isEqualTo(ApplicationStatus.PENDING);
		assertThat(pending.getDecisionDate()).isNull();
		assertThat(pending.getApprovedVenue()).isNull();
		var unchanged = userRepo.findById(applicant.getId()).orElseThrow();
		assertThat(unchanged.getStatus()).isEqualTo(UserStatus.PENDING_VENUE_REQUEST);
		assertThat(unchanged.getRoles()).noneMatch(role -> role.getName().equals(RoleEnum.ROLE_VENUE.name()));
		assertThat(venueRepo.findAllByOwnerId(applicant.getId())).isEmpty();
		assertThat(venueProfileRepo.count()).isZero();
		assertThat(notifications.count()).isZero();
		assertThat(receipts.count()).isZero();
	}

	private void assertDecisionNotification(UUID applicationId, ApplicationStatus status) {
		var inbox = notifications.findAll();
		assertThat(inbox).hasSize(1);
		var notification = inbox.getFirst();
		assertThat(notification.getRecipientId()).isEqualTo(applicant.getId());
		assertThat(notification.getType()).isEqualTo(NotificationType.valueOf("VENUE_APPLICATION_" + status.name()));
		assertThat(notification.isRead()).isFalse();
		assertThat(notification.getSourceEventId()).isNotNull();
		assertThat(notification.getPayload()).containsExactlyInAnyOrderEntriesOf(Map.of(
				"module", "VENUE_APPLICATION", "applicationId", applicationId.toString(),
				"applicantUserId", applicant.getId().toString(), "status", status.name(),
				"action", "APPLICATION_" + status.name()));
		assertThat(receipts.count()).isEqualTo(1);
		assertThat(receipts.findById(notification.getSourceEventId()).orElseThrow().getRecipientId())
				.isEqualTo(applicant.getId());
	}
}
