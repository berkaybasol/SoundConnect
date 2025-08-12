package com.berkayb.soundconnect.modules.profile.VenueProfile.controller.user;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@Tag("web")
class UserVenueProfileControllerTest {
	
	@Autowired MockMvc mockMvc;
	
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	
	@Autowired UserRepository userRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired VenueProfileRepository venueProfileRepository;
	
	// Diğer testlerden gelebilecek referansları temizlemek için
	@Autowired VenueApplicationRepository venueApplicationRepository;
	
	// Başka modüller bean isterse patlamasın diye
	@MockitoBean
	RabbitTemplate rabbitTemplate;
	
	City city;
	District district;
	Neighborhood neighborhood;
	User ownerUser;
	Venue venue;
	VenueProfile profile;
	
	@BeforeEach
	void setup() {
		// Temizlik: önce child tablolar
		venueProfileRepository.deleteAll();
		venueRepository.deleteAll();
		venueApplicationRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		// Location seed
		city = cityRepository.save(City.builder().name("City_" + UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("District").city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("Neighborhood").district(district).build());
		
		// User
		ownerUser = userRepository.save(User.builder()
		                                    .username("user_" + UUID.randomUUID())
		                                    .password("pw")
		                                    .provider(AuthProvider.LOCAL)
		                                    .emailVerified(true)
		                                    .city(city)
		                                    .build());
		
		// Venue (owner: ownerUser)
		venue = venueRepository.save(Venue.builder()
		                                  .name("My Venue")
		                                  .address("Addr 1")
		                                  .city(city)
		                                  .district(district)
		                                  .neighborhood(neighborhood)
		                                  .owner(ownerUser)
		                                  .phone("5550001111")
		                                  .status(VenueStatus.APPROVED)
		                                  .build());
		
		// Profile (mevcut)
		profile = venueProfileRepository.save(VenueProfile.builder()
		                                                  .venue(venue)
		                                                  .bio("old bio")
		                                                  .instagramUrl("https://ig/old")
		                                                  .youtubeUrl(null)
		                                                  .websiteUrl(null)
		                                                  .profilePicture(null)
		                                                  .build());
		
		// SecurityContext’e principal koy
		var principal = new UserDetailsImpl(ownerUser);
		var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(auth);
	}
	
	@Test
	void getMyVenueProfiles_ok() throws Exception {
		mockMvc.perform(get("/api/v1/user/venue-profiles/me"))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
		       .andExpect(jsonPath("$.data[0].venueId").value(venue.getId().toString()))
		       .andExpect(jsonPath("$.data[0].venueName").value("My Venue"))
		       .andExpect(jsonPath("$.data[0].bio").value("old bio"));
	}
	
	@Test
	void updateMyVenueProfile_ok() throws Exception {
		String body = """
        {
          "bio": "new bio",
          "instagramUrl": "https://ig/new",
          "websiteUrl": "https://site.new"
        }
        """;
		mockMvc.perform(put("/api/v1/user/venue-profiles/update/{venueId}", venue.getId())
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.venueId").value(venue.getId().toString()))
		       .andExpect(jsonPath("$.data.bio").value("new bio"))
		       .andExpect(jsonPath("$.data.instagramUrl").value("https://ig/new"))
		       .andExpect(jsonPath("$.data.websiteUrl").value("https://site.new"));
	}
	
	@Test
	void getMyVenueProfiles_userHasNoVenue_should_404() throws Exception {
		// login’i venue’suz kullanıcıyla değiştir
		User noVenueUser = userRepository.save(User.builder()
		                                           .username("empty_" + UUID.randomUUID())
		                                           .password("pw")
		                                           .provider(AuthProvider.LOCAL)
		                                           .emailVerified(true)
		                                           .city(city)
		                                           .build());
		var principal = new UserDetailsImpl(noVenueUser);
		var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(auth);
		
		mockMvc.perform(get("/api/v1/user/venue-profiles/me"))
		       .andDo(print())
		       .andExpect(status().isNotFound())
		       .andExpect(jsonPath("$.httpStatus").value("NOT_FOUND"));
	}
}