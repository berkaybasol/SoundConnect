package com.berkayb.soundconnect.modules.application.venueapplication.controller.admin;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
class VenueApplicationAdminControllerTest {
	
	private static final String BASE = "/api/v1/admin/venue-applications";
	
	@Autowired MockMvc mockMvc;
	
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired VenueApplicationRepository venueApplicationRepository;
	
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	
	// Mail queue vs. ihtiyacı olmasın
	@MockitoBean RabbitTemplate rabbitTemplate;
	// Profile oluşturmayı stub’layalım
	@MockitoBean VenueProfileService venueProfileService;
	
	private User admin;
	private Role roleVenue;
	
	private City city;
	private District district;
	private Neighborhood neighborhood;
	
	@BeforeEach
	void setup() {
		// --- temizle (FK sırasına dikkat) ---
		venueRepository.deleteAll();
		venueApplicationRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		roleRepository.deleteAll();
		
		// --- seed role ---
		roleVenue = roleRepository.save(Role.builder().name(RoleEnum.ROLE_VENUE.name()).build());
		
		// --- seed location ---
		city = cityRepository.save(City.builder().name("AdminCity_"+UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("AdminDistrict").city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("AdminNeighborhood").district(district).build());
		
		// --- seed admin & auth context ---
		admin = userRepository.save(User.builder()
		                                .username("admin_"+UUID.randomUUID())
		                                .password("x")
		                                .provider(AuthProvider.LOCAL)
		                                .emailVerified(true)
		                                .city(city)
		                                .build());
		
		var principal = new UserDetailsImpl(admin);
		var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(auth);
		
		// --- venue profile create stub ---
		Mockito.when(venueProfileService.createProfile(
				Mockito.any(UUID.class),
				Mockito.any(VenueProfileSaveRequestDto.class)
		)).thenReturn(null);
	}
	
	private User seedApplicant() {
		return userRepository.save(User.builder()
		                               .username("applicant_"+UUID.randomUUID())
		                               .password("x")
		                               .provider(AuthProvider.LOCAL)
		                               .emailVerified(true)
		                               .city(city)
		                               .phone("05551234567")
		                               .build());
	}
	
	private VenueApplication seedApp(User applicant, ApplicationStatus status) {
		return venueApplicationRepository.save(VenueApplication.builder()
		                                                       .applicant(applicant)
		                                                       .venueName("My Venue "+UUID.randomUUID())
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
		
		mockMvc.perform(post(BASE + "/approve/{id}", app.getId()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.status").value("APPROVED"))
		       .andExpect(jsonPath("$.data.id").value(app.getId().toString()));
		
		// yan etkiler: applicant'a ROLE_VENUE atanmış mı?
		var refreshed = userRepository.findById(applicant.getId()).orElseThrow();
		boolean hasVenueRole = refreshed.getRoles().stream()
		                                .anyMatch(r -> r.getName().equals(RoleEnum.ROLE_VENUE.name()));
		org.assertj.core.api.Assertions.assertThat(hasVenueRole).isTrue();
		
		// venue oluşmuş mu?
		List<Venue> venues = venueRepository.findAll();
		org.assertj.core.api.Assertions.assertThat(venues).isNotEmpty();
		org.assertj.core.api.Assertions.assertThat(venues.get(0).getOwner().getId()).isEqualTo(applicant.getId());
	}
	
	@Test
	void approve_should_fail_when_not_pending() throws Exception {
		var applicant = seedApplicant();
		var app = seedApp(applicant, ApplicationStatus.REJECTED); // veya APPROVED
		
		mockMvc.perform(post(BASE + "/approve/{id}", app.getId()))
		       .andDo(print())
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
		       .andExpect(jsonPath("$.message", containsString("Invalid"))); // ErrorType mesajına göre esnek
	}
	
	@Test
	void reject_ok() throws Exception {
		var applicant = seedApplicant();
		var app = seedApp(applicant, ApplicationStatus.PENDING);
		
		mockMvc.perform(post(BASE + "/reject/{id}", app.getId())
				                .param("reason", "Eksik bilgi"))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.status").value("REJECTED"));
	}
	
	@Test
	void get_by_status_ok() throws Exception {
		var a1 = seedApplicant();
		var a2 = seedApplicant();
		var a3 = seedApplicant();
		
		seedApp(a1, ApplicationStatus.PENDING);
		seedApp(a2, ApplicationStatus.PENDING);
		seedApp(a3, ApplicationStatus.APPROVED);
		
		mockMvc.perform(get(BASE + "/by-status").param("status", "PENDING"))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(2)));
	}
	
	@Test
	void get_by_id_ok() throws Exception {
		var applicant = seedApplicant();
		var app = seedApp(applicant, ApplicationStatus.PENDING);
		
		mockMvc.perform(get(BASE + "/{id}", app.getId()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.id").value(app.getId().toString()))
		       .andExpect(jsonPath("$.data.applicantUsername").value(applicant.getUsername()));
	}
}