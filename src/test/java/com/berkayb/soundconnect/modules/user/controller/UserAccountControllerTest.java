package com.berkayb.soundconnect.modules.user.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.controller.user.UserAccountController;
import com.berkayb.soundconnect.modules.user.dto.request.UsernameChangeRequestDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.service.UserService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserAccountController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserAccountControllerTest {
	@Resource MockMvc mockMvc;

	@MockitoBean UserService userService;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void changeUsernameUsesAuthenticatedUuidAndReturnsCanonicalUsername() throws Exception {
		UUID userId = UUID.randomUUID();
		UsernamePasswordAuthenticationToken authentication = authenticationFor(userId, "oldname");
		when(userService.changeUsername(any(), any())).thenReturn("berkay");

		mockMvc.perform(patch(EndPoints.User.BASE + EndPoints.User.MY_USERNAME)
				                .with(authentication(authentication))
				                .contentType("application/json")
				                .content("""
						                {"username":" BeRKay "}
						                """))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data").value("berkay"));

		verify(userService).changeUsername(
				userId,
				new UsernameChangeRequestDto("berkay")
		);
	}

	@Test
	void changeUsernameRejectsValueThatIsTooShortAfterCanonicalization() throws Exception {
		UsernamePasswordAuthenticationToken authentication = authenticationFor(UUID.randomUUID(), "oldname");

		mockMvc.perform(patch(EndPoints.User.BASE + EndPoints.User.MY_USERNAME)
				                .with(authentication(authentication))
				                .contentType("application/json")
				                .content("""
						                {"username":" a "}
						                """))
		       .andExpect(status().isBadRequest());

		verifyNoInteractions(userService);
	}

	@Test
	void changeUsernameReturnsStableConflictContractWhenCooldownIsActive() throws Exception {
		UUID userId = UUID.randomUUID();
		UsernamePasswordAuthenticationToken authentication = authenticationFor(userId, "oldname");
		when(userService.changeUsername(any(), any()))
				.thenThrow(new SoundConnectException(ErrorType.USERNAME_CHANGE_COOLDOWN_ACTIVE));

		mockMvc.perform(patch(EndPoints.User.BASE + EndPoints.User.MY_USERNAME)
				                .with(authentication(authentication))
				                .contentType("application/json")
				                .content("""
						                {"username":"newname"}
						                """))
		       .andExpect(status().isConflict())
		       .andExpect(jsonPath("$.code").value(1005))
		       .andExpect(jsonPath("$.message")
				                  .value("Kullanıcı adını değiştirdikten sonra 30 gün boyunca yeniden değiştiremezsin."))
		       .andExpect(jsonPath("$.details[0]")
				                  .value("Kullanıcı adını değiştirdikten sonra 30 gün boyunca yeniden değiştiremezsin."));
	}

	private UsernamePasswordAuthenticationToken authenticationFor(UUID userId, String username) {
		UserDetailsImpl principal = new UserDetailsImpl(User.builder()
				.id(userId)
				.username(username)
				.build());
		UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(principal, null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
		return authentication;
	}
}
