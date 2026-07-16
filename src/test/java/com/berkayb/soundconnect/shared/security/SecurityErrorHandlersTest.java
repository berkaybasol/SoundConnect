package com.berkayb.soundconnect.shared.security;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SecurityErrorHandlersTest {

	@Mock SecurityErrorResponseWriter responseWriter;
	@Mock HttpServletRequest request;
	@Mock HttpServletResponse response;

	@Test
	void authenticationEntryPointUsesTheSharedUnauthorizedContract() throws Exception {
		RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(responseWriter);

		entryPoint.commence(request, response, new BadCredentialsException("not exposed"));

		verify(responseWriter).write(request, response, ErrorType.UNAUTHORIZED);
	}

	@Test
	void accessDeniedHandlerUsesTheSharedForbiddenContract() throws Exception {
		RestAccessDeniedHandler handler = new RestAccessDeniedHandler(responseWriter);

		handler.handle(request, response, new AccessDeniedException("not exposed"));

		verify(responseWriter).write(request, response, ErrorType.FORBIDDEN_ACCESS);
	}
}
