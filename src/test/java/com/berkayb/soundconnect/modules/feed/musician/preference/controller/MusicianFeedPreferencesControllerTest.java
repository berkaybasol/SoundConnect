package com.berkayb.soundconnect.modules.feed.musician.preference.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesUpdate;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianInstrumentSummary;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.OpportunityCitySummary;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MusicianFeedPreferencesController.class, excludeFilters = @ComponentScan.Filter(
		type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(MusicianFeedPreferencesControllerTest.MethodSecurity.class)
class MusicianFeedPreferencesControllerTest {
	@TestConfiguration
	@EnableMethodSecurity
	static class MethodSecurity {}

	private static final String ENDPOINT = "/api/v1/feed/musician/preferences";

	@Autowired MockMvc mvc;
	@MockitoBean MusicianFeedPreferencesService service;

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void ownerReadReturnsVersionedPrivateNoStoreContract() throws Exception {
		UUID userId = authenticate("ROLE_MUSICIAN");
		UUID cityId = UUID.randomUUID();
		UUID instrumentId = UUID.randomUUID();
		when(service.get(userId)).thenReturn(response(cityId, instrumentId));

		mvc.perform(get(ENDPOINT))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(header().string("Cache-Control", containsString("private")))
				.andExpect(jsonPath("$.data.contractVersion").value(1))
				.andExpect(jsonPath("$.data.version").value(3))
				.andExpect(jsonPath("$.data.opportunityCity.id").value(cityId.toString()))
				.andExpect(jsonPath("$.data.opportunityCity.name").value("İstanbul"))
				.andExpect(jsonPath("$.data.instruments[0].id").value(instrumentId.toString()))
				.andExpect(jsonPath("$.data.instruments[0].name").value("Gitar"))
				.andExpect(jsonPath("$.data.completion.criteriaVersion").value(1))
				.andExpect(jsonPath("$.data.completion.personalizationReadiness.complete").value(true))
				.andExpect(jsonPath("$.data.completion.incompleteTasks[0].code")
						.value("STAGE_NAME_AND_BIO"));
		verify(service).get(userId);
	}

	@Test
	void putUsesOnlyAuthenticatedIdentityAndAcceptsExplicitCityClear() throws Exception {
		UUID userId = authenticate("ROLE_MUSICIAN");
		when(service.update(eq(userId), any())).thenReturn(blankResponse(4));

		mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
						.content("{\"opportunityCityId\":null,\"expectedVersion\":3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.version").value(4))
				.andExpect(jsonPath("$.data.opportunityCity").doesNotExist());
		verify(service).update(eq(userId), argThat(update -> update.opportunityCityId() == null
				&& update.expectedVersion() == 3));
	}

	@Test
	void actorIdsAndUnknownFieldsAreRejectedRatherThanIgnored() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
						.content("{\"opportunityCityId\":null,\"expectedVersion\":0,\"userId\":\""
								+ UUID.randomUUID() + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(4002));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{}",
			"{\"opportunityCityId\":null}",
			"{\"expectedVersion\":0}",
			"{\"opportunityCityId\":null,\"expectedVersion\":-1}",
			"{\"opportunityCityId\":null,\"opportunityCityId\":null,\"expectedVersion\":0}"
	})
	void incompleteDuplicateOrInvalidCommandsAreRejectedBeforeTheService(String body) throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void malformedCityIdIsAStableBadRequest() throws Exception {
		authenticate("ROLE_MUSICIAN");
		mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
						.content("{\"opportunityCityId\":\"not-a-uuid\",\"expectedVersion\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(4002));
		verifyNoInteractions(service);
	}

	@Test
	void versionConflictIsExposedAs409WithoutInternalDetails() throws Exception {
		UUID userId = authenticate("ROLE_MUSICIAN");
		when(service.update(eq(userId), any())).thenThrow(
				new SoundConnectException(ErrorType.MUSICIAN_FEED_PREFERENCE_VERSION_CONFLICT));

		mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
						.content("{\"opportunityCityId\":null,\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(1317));
	}

	@Test
	void anotherProfileRoleCannotReadOrWritePrivatePreferences() throws Exception {
		authenticate("ROLE_VENUE");
		mvc.perform(get(ENDPOINT)).andExpect(status().isForbidden());
		mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
						.content("{\"opportunityCityId\":null,\"expectedVersion\":0}"))
				.andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}

	@Test
	void unauthenticatedAccessIsRejected() throws Exception {
		mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
		mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
						.content("{\"opportunityCityId\":null,\"expectedVersion\":0}"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(service);
	}

	private UUID authenticate(String roleName) {
		UUID id = UUID.randomUUID();
		User user = User.builder().id(id).username("feed_owner").password("unused")
				.email("feed-owner@example.com").emailVerified(true).status(UserStatus.ACTIVE)
				.roles(Set.of(Role.builder().name(roleName).build())).build();
		UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
		return id;
	}

	private static MusicianFeedPreferencesResponse response(UUID cityId, UUID instrumentId) {
		var progress = new MusicianFeedCompletionResponse.Progress(true, 2, 2, 100);
		var profileProgress = new MusicianFeedCompletionResponse.Progress(false, 1, 4, 25);
		var overall = new MusicianFeedCompletionResponse.Progress(false, 2, 5, 40);
		var completion = new MusicianFeedCompletionResponse(1, progress, profileProgress, overall,
				List.of(new MusicianFeedCompletionResponse.IncompleteTask(
						MusicianFeedCompletionTaskCode.STAGE_NAME_AND_BIO, 3)));
		return new MusicianFeedPreferencesResponse(1, 3,
				new OpportunityCitySummary(cityId, "İstanbul"),
				List.of(new MusicianInstrumentSummary(instrumentId, "Gitar")), completion);
	}

	private static MusicianFeedPreferencesResponse blankResponse(long version) {
		var none = new MusicianFeedCompletionResponse.Progress(false, 0, 2, 0);
		var publicProfile = new MusicianFeedCompletionResponse.Progress(false, 0, 4, 0);
		var overall = new MusicianFeedCompletionResponse.Progress(false, 0, 5, 0);
		return new MusicianFeedPreferencesResponse(1, version, null, List.of(),
				new MusicianFeedCompletionResponse(1, none, publicProfile, overall, List.of()));
	}
}
