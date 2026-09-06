package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse.MusicianCalendarRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BandCalendarController.class, excludeFilters = @ComponentScan.Filter(
		type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(BandCalendarControllerTest.MethodSecurity.class)
class BandCalendarControllerTest {
	@TestConfiguration @EnableMethodSecurity static class MethodSecurity {}
	@Autowired MockMvc mvc;
	@MockitoBean BandCalendarService service;
	@MockitoBean MusicianCalendarRateLimitGuard guard;
	private final UUID bandId = UUID.randomUUID();
	private String path() { return "/api/v1/user/bands/" + bandId + "/calendar-settings"; }
	@AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

	@Test void publicOptedOutCalendarUsesSharedContractAndNoStore() throws Exception {
		LocalDate date = LocalDate.of(2026, 9, 5);
		when(service.getCalendar(bandId, date, date, 0, 20)).thenReturn(new MusicianCalendarResponse(bandId, date, date, false, List.of(), 0, 20, false));
		mvc.perform(get("/api/v1/public/bands/{bandId}/calendar", bandId).param("startDate", date.toString()).param("endDate", date.toString()))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(header().string("Cache-Control", containsString("private")))
				.andExpect(jsonPath("$.data.profileId").value(bandId.toString())).andExpect(jsonPath("$.data.visible").value(false));
	}

	@Test void retiredWriteCannotAlterBandExposure() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON).content("{\"visible\":true,\"version\":0}"))
				.andExpect(status().isGone());
		verifyNoInteractions(service, guard);
	}

	@Test void unauthorizedMusicianCannotConsumeBandBudget() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON).content("{\"visible\":true,\"version\":0}"))
				.andExpect(status().isGone());
		verifyNoInteractions(guard);
		verify(service, never()).updateSettings(any(), any(), any());
	}

	@Test void retiredReadDoesNotConsumeRateLimit() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(get(path())).andExpect(status().isGone());
		verifyNoInteractions(service, guard);
	}

	@Test void retiredWriteDoesNotDependOnRedis() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON).content("{\"visible\":true,\"version\":0}"))
				.andExpect(status().isGone());
		verifyNoInteractions(service, guard);
	}

	@Test void venueCannotReadOrMutateBandSettings() throws Exception {
		authenticate("ROLE_VENUE");
		mvc.perform(get(path())).andExpect(status().isForbidden());
		mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON).content("{\"visible\":true,\"version\":0}"))
				.andExpect(status().isForbidden());
		verifyNoInteractions(service, guard);
	}

	@Test void guestCannotReadSettings() throws Exception {
		mvc.perform(get(path())).andExpect(status().isUnauthorized());
		verifyNoInteractions(service, guard);
	}

	@ParameterizedTest @ValueSource(strings = {"{}", "{\"visible\":true}", "{\"visible\":true,\"version\":-1}"})
	void invalidBodyNeverReachesService(String json) throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
		verifyNoInteractions(service, guard);
	}

	private UUID authenticate(String role) {
		UUID id = UUID.randomUUID();
		User user = User.builder().id(id).username("band_calendar_owner").password("unused").email("calendar@example.com")
				.emailVerified(true).status(UserStatus.ACTIVE).roles(Set.of(Role.builder().name(role).build())).build();
		UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
		return id;
	}
}
