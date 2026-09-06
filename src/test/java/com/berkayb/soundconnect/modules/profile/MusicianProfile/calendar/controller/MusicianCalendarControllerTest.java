package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse.MusicianCalendarRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.service.MusicianCalendarService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
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

@WebMvcTest(controllers = MusicianCalendarController.class, excludeFilters = @ComponentScan.Filter(
		type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(MusicianCalendarControllerTest.MethodSecurity.class)
class MusicianCalendarControllerTest {
	@TestConfiguration @EnableMethodSecurity static class MethodSecurity {}
	@Autowired MockMvc mvc;
	@MockitoBean MusicianCalendarService service;
	@MockitoBean MusicianCalendarRateLimitGuard guard;
	private static final String SETTINGS = "/api/v1/user/musician-profiles/me/calendar-settings";
	@AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

	@Test
	void publicCalendarIsReadableWithoutAuthenticationAndNeverCacheable() throws Exception {
		UUID profileId = UUID.randomUUID();
		LocalDate start = LocalDate.of(2026, 9, 5), end = start.plusDays(6);
		when(service.getCalendar(profileId, start, end, 0, 20))
				.thenReturn(new MusicianCalendarResponse(profileId, start, end, false, List.of(), 0, 20, false));
		mvc.perform(get("/api/v1/public/musician-profiles/{profileId}/calendar", profileId)
				.param("startDate", start.toString()).param("endDate", end.toString()))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(header().string("Cache-Control", containsString("private")))
				.andExpect(jsonPath("$.data.profileId").value(profileId.toString()))
				.andExpect(jsonPath("$.data.startDate").value(start.toString()))
				.andExpect(jsonPath("$.data.visible").value(false))
				.andExpect(jsonPath("$.data.events").isEmpty());
	}

	@Test
	void retiredWriteCannotAlterProfileExposure() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(SETTINGS).contentType(MediaType.APPLICATION_JSON)
				.content("{\"visible\":false,\"version\":0,\"userId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isGone());
		verifyNoInteractions(guard, service);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"visible\":false}", "{\"version\":0}", "{\"visible\":true,\"version\":-1}"})
	void missingAndInvalidRequiredFieldsAreRejectedBeforeRateLimitOrMutation(String body) throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		verifyNoInteractions(guard, service);
	}

	@Test
	void venueCannotReadOrMutateMusicianSettings() throws Exception {
		authenticate("ROLE_VENUE");
		mvc.perform(get(SETTINGS)).andExpect(status().isForbidden());
		mvc.perform(put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content("{\"visible\":false,\"version\":0}"))
				.andExpect(status().isForbidden());
		verifyNoInteractions(guard, service);
	}

	@Test
	void unauthenticatedSettingsReadIsRejected() throws Exception {
		mvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
		verifyNoInteractions(guard, service);
	}

	@Test
	void retiredReadDirectsOldClientsToUpdate() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(get(SETTINGS)).andExpect(status().isGone()).andExpect(jsonPath("$.code").value(1316));
		verifyNoInteractions(service, guard);
	}

	@Test
	void retiredWriteDoesNotDependOnRedis() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content("{\"visible\":false,\"version\":0}"))
				.andExpect(status().isGone());
		verifyNoInteractions(service, guard);
	}

	private UUID authenticate(String role) {
		UUID id = UUID.randomUUID();
		User user = User.builder().id(id).username("calendar_owner").password("unused").email("calendar@example.com")
				.emailVerified(true).status(UserStatus.ACTIVE).roles(Set.of(Role.builder().name(role).build())).build();
		UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
		return id;
	}
}
