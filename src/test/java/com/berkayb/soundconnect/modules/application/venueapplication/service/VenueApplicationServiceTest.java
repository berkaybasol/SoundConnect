package com.berkayb.soundconnect.modules.application.venueapplication.service;

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
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
class VenueApplicationServiceTest {
	
	@Autowired VenueApplicationService venueApplicationService;
	@Autowired VenueApplicationRepository venueAppRepo;
	@Autowired UserRepository userRepo;
	@Autowired CityRepository cityRepo;
	@Autowired DistrictRepository districtRepo;
	@Autowired NeighborhoodRepository neighborhoodRepo;
	@Autowired RoleRepository roleRepo;
	@Autowired VenueRepository venueRepo;
	@Autowired VenueProfileRepository venueProfileRepo;
	
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
		venueProfileRepo.deleteAll();
		venueRepo.deleteAll();
		venueAppRepo.deleteAll();
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
		                              .username("user_" + UUID.randomUUID())
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
		assertThat(dto.venueName()).isEqualTo("Cool Venue");
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
		assertThat(v.getName()).isEqualTo("Cool Venue");
		assertThat(v.getAddress()).isEqualTo("Some Address 123");
		assertThat(v.getCity().getId()).isEqualTo(city.getId());
		assertThat(v.getDistrict().getId()).isEqualTo(district.getId());
		if (neighborhood.getId() != null) {
			assertThat(v.getNeighborhood().getId()).isEqualTo(neighborhood.getId());
		}
		// profil otomatik yaratıldı mı?
		assertThat(venueProfileRepo.findByVenueId(v.getId())).isPresent();
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
	}
}