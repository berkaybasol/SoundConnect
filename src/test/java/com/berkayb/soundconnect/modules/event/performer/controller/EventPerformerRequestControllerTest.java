package com.berkayb.soundconnect.modules.event.performer.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestResponseDto;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.response.PageResponse;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
		controllers = EventPerformerRequestController.class,
		excludeFilters = @ComponentScan.Filter(
				type = FilterType.ASSIGNABLE_TYPE,
				classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class
		)
)
@AutoConfigureMockMvc(addFilters = false)
@Import(EventPerformerRequestControllerTest.MethodSecurity.class)
class EventPerformerRequestControllerTest {
	@TestConfiguration @EnableMethodSecurity static class MethodSecurity {}

	@Autowired private MockMvc mockMvc;
	@MockitoBean private EventPerformerRequestService service;

	@Test
	void mineReturnsStablePagedContractAndPrivateRequestId() throws Exception {
		UUID userId = UUID.randomUUID();
		authenticateMusician(userId);
		EventPerformerRequestResponseDto item = response(EventPerformerRequestStatus.PENDING);
		PageResponse<EventPerformerRequestResponseDto> page = new PageResponse<>(
				List.of(item), 0, 20, 1, 1, true, true
		);
		when(service.getMine(
				userId,
				EventPerformerRequestStatus.PENDING,
				null,
				null,
				0,
				20
		)).thenReturn(page);

		mockMvc.perform(get("/api/v1/event-performer-requests/mine")
					.param("status", "PENDING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].id").value(item.id().toString()))
				.andExpect(jsonPath("$.data.content[0].venueProfilePictureUrl")
						.value("https://cdn.test/venue.jpg"))
				.andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
				.andExpect(jsonPath("$.data.content[0].profileCalendarApproved").value(false))
				.andExpect(jsonPath("$.data.content[0].posterImage").value("https://cdn.test/poster.jpg"))
				.andExpect(jsonPath("$.data.page").value(0))
				.andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void reconsiderForwardsExplicitChoiceThroughDedicatedEndpoint(boolean choice) throws Exception {
		UUID user = UUID.randomUUID(), request = UUID.randomUUID();
		authenticateMusician(user);
		when(service.reconsider(user, request, choice)).thenReturn(response(EventPerformerRequestStatus.ACCEPTED, choice));
		mockMvc.perform(post("/api/v1/event-performer-requests/{id}/reconsider", request)
				.contentType("application/json").content("{\"showOnProfile\":" + choice + "}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.data.decisionAllowed").value(false))
				.andExpect(jsonPath("$.data.canReconsider").value(false))
				.andExpect(jsonPath("$.data.eventStartsAt").value("2026-09-12T18:00:00Z"));
		verify(service).reconsider(user, request, choice);
		verify(service, never()).accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@ParameterizedTest @ValueSource(strings = {"", "{}", "null", "{\"showOnProfile\":null}",
			"{\"showOnProfile\":1}", "{\"showOnProfile\":\"true\"}"})
	void reconsiderMissingOrCoercedChoiceCannotReachService(String body) throws Exception {
		authenticateMusician(UUID.randomUUID());
		mockMvc.perform(post("/api/v1/event-performer-requests/{id}/reconsider", UUID.randomUUID())
				.contentType("application/json").content(body)).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test void reconsiderRequiresMusicianRole() throws Exception {
		authenticate(UUID.randomUUID(), "ROLE_VENUE");
		mockMvc.perform(post("/api/v1/event-performer-requests/{id}/reconsider", UUID.randomUUID())
				.contentType("application/json").content("{\"showOnProfile\":false}"))
				.andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}

	@Test
	void mineForwardsPairedMusicianTargetScope() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID musicianProfileId = UUID.randomUUID();
		authenticateMusician(userId);
		PageResponse<EventPerformerRequestResponseDto> page = new PageResponse<>(
				List.of(), 2, 10, 0, 0, false, true
		);
		when(service.getMine(
				userId,
				EventPerformerRequestStatus.PENDING,
				PerformerType.MUSICIAN,
				musicianProfileId,
				2,
				10
		)).thenReturn(page);

		mockMvc.perform(get("/api/v1/event-performer-requests/mine")
					.param("status", "PENDING")
					.param("targetType", "MUSICIAN")
					.param("targetId", musicianProfileId.toString())
					.param("page", "2")
					.param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.page").value(2))
				.andExpect(jsonPath("$.data.size").value(10));

		verify(service).getMine(
				userId,
				EventPerformerRequestStatus.PENDING,
				PerformerType.MUSICIAN,
				musicianProfileId,
				2,
				10
		);
	}

	@Test
	void mineRejectsUnpairedTargetScopeBeforeCallingService() throws Exception {
		UUID userId = UUID.randomUUID();
		authenticateMusician(userId);

		mockMvc.perform(get("/api/v1/event-performer-requests/mine")
					.param("targetType", "BAND"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(9251));

		verify(service, never()).getMine(
				eq(userId),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyInt()
		);
	}

	@Test
	void mineRejectsManualTargetScope() throws Exception {
		UUID userId = UUID.randomUUID();
		authenticateMusician(userId);

		mockMvc.perform(get("/api/v1/event-performer-requests/mine")
					.param("targetType", "MANUAL")
					.param("targetId", UUID.randomUUID().toString()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(9251));
	}

	@Test
	void acceptUsesAuthenticatedActorRatherThanRequestBodyIdentity() throws Exception {
		UUID userId = UUID.randomUUID();
		authenticateMusician(userId);
		EventPerformerRequestResponseDto accepted = response(EventPerformerRequestStatus.ACCEPTED);
		when(service.accept(userId, accepted.id(), null)).thenReturn(accepted);

		mockMvc.perform(post("/api/v1/event-performer-requests/{requestId}/accept", accepted.id()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.data.profileCalendarApproved").value(false));
		verify(service).accept(eq(userId), eq(accepted.id()), isNull());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void acceptForwardsOnlyExplicitBooleanChoiceAndAuthenticatedActor(boolean showOnProfile) throws Exception {
		UUID userId = UUID.randomUUID();
		authenticateMusician(userId);
		var accepted = response(EventPerformerRequestStatus.ACCEPTED, showOnProfile);
		when(service.accept(userId, accepted.id(), showOnProfile)).thenReturn(accepted);
		mockMvc.perform(post("/api/v1/event-performer-requests/{requestId}/accept", accepted.id())
				.contentType("application/json")
				.content("{\"showOnProfile\":" + showOnProfile + ",\"actorUserId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.profileCalendarApproved").value(showOnProfile));
		verify(service).accept(userId, accepted.id(), showOnProfile);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"showOnProfile\":null}", "null"})
	void absentOrNullChoiceRemainsUnspecifiedForPurposeAwareDefault(String body) throws Exception {
		UUID userId = UUID.randomUUID();
		authenticateMusician(userId);
		var accepted = response(EventPerformerRequestStatus.ACCEPTED);
		when(service.accept(userId, accepted.id(), null)).thenReturn(accepted);
		mockMvc.perform(post("/api/v1/event-performer-requests/{requestId}/accept", accepted.id())
				.contentType("application/json").content(body))
				.andExpect(status().isOk());
		verify(service).accept(eq(userId), eq(accepted.id()), isNull());
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"true\"", "\"false\"", "1", "0", "{}", "[]"})
	void acceptRejectsCoercedPublicationConsentBeforeCallingService(String value) throws Exception {
		authenticateMusician(UUID.randomUUID());
		mockMvc.perform(post("/api/v1/event-performer-requests/{requestId}/accept", UUID.randomUUID())
				.contentType("application/json").content("{\"showOnProfile\":" + value + "}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void changedFinalizedChoiceIsReturnedAsConflictRatherThanAnotherAcceptance() throws Exception {
		UUID actor = UUID.randomUUID(), request = UUID.randomUUID();
		authenticateMusician(actor);
		when(service.accept(actor, request, true)).thenThrow(new com.berkayb.soundconnect.shared.exception.SoundConnectException(
				com.berkayb.soundconnect.shared.exception.ErrorType.EVENT_PERFORMER_REQUEST_FINALIZED));
		mockMvc.perform(post("/api/v1/event-performer-requests/{requestId}/accept", request)
				.contentType("application/json").content("{\"showOnProfile\":true}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9253));
	}

	@Test
	void venueCannotApproveInvitationBySupplyingPublicationFlag() throws Exception {
		authenticate(UUID.randomUUID(), "ROLE_VENUE");
		mockMvc.perform(post("/api/v1/event-performer-requests/{requestId}/accept", UUID.randomUUID())
				.contentType("application/json").content("{\"showOnProfile\":true}"))
				.andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}

	private EventPerformerRequestResponseDto response(EventPerformerRequestStatus status) {
		return response(status, false);
	}

	private EventPerformerRequestResponseDto response(EventPerformerRequestStatus status, boolean showOnProfile) {
		return new EventPerformerRequestResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				null,
				PerformerType.MUSICIAN,
				"bugrasahin",
				UUID.randomUUID(),
				"SoundConnect Ankara",
				"https://cdn.test/venue.jpg",
				"Gece Konseri",
				LocalDate.of(2026, 9, 12),
				LocalTime.of(21, 0),
				null,
				status,
				com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestPurpose.PERFORMER_CONSENT,
				LocalDateTime.of(2026, 9, 4, 12, 0),
				status == EventPerformerRequestStatus.PENDING ? null : LocalDateTime.of(2026, 9, 4, 12, 5),
				"https://cdn.test/poster.jpg",
				showOnProfile,
				status == EventPerformerRequestStatus.PENDING,
				status == EventPerformerRequestStatus.REJECTED,
				false,
				java.time.Instant.parse("2026-09-06T09:00:00Z"),
				java.time.Instant.parse("2026-09-12T18:00:00Z")
		);
	}

	private void authenticateMusician(UUID userId) {
		authenticate(userId, "ROLE_MUSICIAN");
	}

	private void authenticate(UUID userId, String role) {
		User user = User.builder()
				.id(userId)
				.username("musician")
				.password("unused")
				.email("musician@example.com")
				.emailVerified(true)
				.status(UserStatus.ACTIVE)
				.roles(Set.of(Role.builder().name(role).build()))
				.build();
		UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
		);
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}
}
