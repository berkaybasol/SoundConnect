package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.GoogleAuthRequestDto;
import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.service.GoogleAuthService;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GoogleAuthHttpContractTest {
	private final GoogleAuthService auth = mock(GoogleAuthService.class);
	private MockMvc mvc;

	@BeforeEach void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new GoogleAuthControllerImpl(auth), new GoogleCompleteProfileController(auth))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@Test void accountChoiceResponseWorksWithoutASoundConnectSession() throws Exception {
		when(auth.loginWithGoogle(new GoogleAuthRequestDto("proof")))
				.thenReturn(BaseResponse.<LoginResponse>builder().success(true).code(200).data(LoginResponse.googleProfileRequired()).build());
		mvc.perform(post("/api/v1/auth/google-sign-in").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"proof\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.requiresGoogleProfileCompletion").value(true))
				.andExpect(jsonPath("$.data.token").doesNotExist()).andExpect(jsonPath("$.data.userId").doesNotExist());
	}

	@Test void confirmationRequiresProofUsernameRoleAndAffirmativeChoice() throws Exception {
		for (String body : new String[]{"{\"role\":\"ROLE_LISTENER\"}",
				"{\"idToken\":\"proof\",\"username\":\"user\",\"role\":\"ROLE_LISTENER\"}",
				"{\"idToken\":\"proof\",\"username\":\"x\",\"role\":\"ROLE_MUSICIAN\",\"consent\":true}"}) {
			mvc.perform(post("/api/v1/auth/complete-google-profile").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(4000));
		}
		verifyNoInteractions(auth);
	}

	@Test void confirmedOnboardingReturnsTheSharedMobileSessionShape() throws Exception {
		var form = new GoogleCompleteProfileRequestDto("proof","user", RoleEnum.ROLE_LISTENER,true);
		var id = UUID.randomUUID();
		when(auth.completeProfile(form)).thenReturn(new LoginResponse("session", UserStatus.ACTIVE, id, "user",
				Set.of("ROLE_LISTENER"), Set.of(), false, true));
		mvc.perform(post("/api/v1/auth/complete-google-profile").contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"proof\",\"username\":\" User \",\"role\":\"ROLE_LISTENER\",\"consent\":true}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.token").value("session"))
				.andExpect(jsonPath("$.data.userId").value(id.toString())).andExpect(jsonPath("$.data.username").value("user"))
				.andExpect(jsonPath("$.data.roles[0]").value("ROLE_LISTENER")).andExpect(jsonPath("$.data.admin").value(false))
				.andExpect(jsonPath("$.data.requiresListenerProfileChoice").value(true))
				.andExpect(jsonPath("$.data.requiresGoogleProfileCompletion").value(false));
	}
}
