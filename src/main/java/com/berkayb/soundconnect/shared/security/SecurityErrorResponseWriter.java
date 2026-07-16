package com.berkayb.soundconnect.shared.security;

import com.berkayb.soundconnect.shared.exception.ErrorResponse;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

	private final ObjectMapper objectMapper;

	public void write(
			HttpServletRequest request,
			HttpServletResponse response,
			ErrorType errorType
	) throws IOException {
		if (response.isCommitted()) {
			return;
		}

		ErrorResponse body = ErrorResponse.builder()
				.code(errorType.getCode())
				.message(errorType.getMessage())
				.httpStatus(errorType.getHttpStatus())
				.path(request.getRequestURI())
				.details(List.of(errorType.getDetails()))
				.timestamp(LocalDateTime.now())
				.build();

		response.resetBuffer();
		response.setStatus(errorType.getHttpStatus().value());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader("Cache-Control", "no-store");
		objectMapper.writeValue(response.getOutputStream(), body);
	}
}
