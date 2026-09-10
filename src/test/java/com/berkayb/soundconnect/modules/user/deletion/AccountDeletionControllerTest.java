package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Wire validation/ownership contract. Authentication filters have separate real-security tests. */
class AccountDeletionControllerTest {
    AccountDeletionCommandService commands = mock(AccountDeletionCommandService.class);
    UUID authenticatedUser = UUID.randomUUID();
    MockMvc mvc;
    @BeforeEach void setup() {
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(authenticatedUser);
        mvc = MockMvcBuilders.standaloneSetup(new AccountDeletionController(commands))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == UserDetailsImpl.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) { return principal; }
                }).build();
    }
    @Test void requestBodyCannotSelectAnotherAccount() throws Exception {
        mvc.perform(delete("/api/v1/users/me/account").contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"DELETE\",\"currentPassword\":\"local-password\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        verify(commands).deleteSelf(eq(authenticatedUser), argThat(request -> "local-password".equals(request.currentPassword())));
    }
    @Test void missingConfirmationIsRejectedBeforeTheServiceAndDoesNotEchoCredentials() throws Exception {
        mvc.perform(delete("/api/v1/users/me/account").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"credential-not-to-echo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("credential-not-to-echo"))));
        verifyNoInteractions(commands);
    }
}
