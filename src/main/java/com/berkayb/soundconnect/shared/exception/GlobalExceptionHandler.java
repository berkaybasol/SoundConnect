package com.berkayb.soundconnect.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
	
	// Ozel olarak firlattigimiz SoundConnectionExceptionlari yakaladigimiz metod
	@ExceptionHandler(SoundConnectException.class)
	public ResponseEntity<ErrorResponse> handleSoundConnectException(SoundConnectException e, HttpServletRequest request) {
		ErrorType errorType = e.getErrorType();
		
		ErrorResponse response = ErrorResponse.builder()
		                                      .message(errorType.getMessage())
		                                      .code(errorType.getCode())
		                                      .httpStatus(errorType.getHttpStatus())
		                                      .path(request.getRequestURI())
		                                      .timestamp(LocalDateTime.now())
		                                      .details(e.getDetails() != null ? e.getDetails() : Collections.singletonList(errorType.getDetails()))
		                                      .build();
		
		return new ResponseEntity<>(response, errorType.getHttpStatus());
	}
	
	// Bilinmeyen hatalari burada karsiliyoruz(NullPointerExceotin, IllegalStateException vs
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(Exception e, HttpServletRequest request) {
		log.error("Unexpected error occured: ", e);
		
		ErrorResponse response = ErrorResponse
				.builder()
				.message("Unexpected server error")
				.code(9999)
				.httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.path(request.getRequestURI())
				.timestamp(LocalDateTime.now())
				.build();
		return new ResponseEntity<>(response, org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
	}
	
	// DTO validation hatası (örn: @Valid anotasyonlu alanlar)
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e, HttpServletRequest request) {
		List<String> details = e.getBindingResult().getFieldErrors().stream()
		                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
		                        .collect(Collectors.toList());
		
		ErrorResponse response = ErrorResponse.builder()
		                                      .message("Validation failed")
		                                      .code(ErrorType.VALIDATION_ERROR.getCode())
		                                      .httpStatus(ErrorType.VALIDATION_ERROR.getHttpStatus())
		                                      .path(request.getRequestURI())
		                                      .timestamp(LocalDateTime.now())
		                                      .details(details)
		                                      .build();
		
		return new ResponseEntity<>(response, ErrorType.VALIDATION_ERROR.getHttpStatus());
	}
	
	// Örneğin: /api/user?id= eksik parametre gibi durumlar
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParams(MissingServletRequestParameterException e, HttpServletRequest request) {
		ErrorResponse response = ErrorResponse.builder()
		                                      .message("Missing request parameter: " + e.getParameterName())
		                                      .code(4001)
		                                      .httpStatus(HttpStatus.BAD_REQUEST)
		                                      .path(request.getRequestURI())
		                                      .timestamp(LocalDateTime.now())
		                                      .build();
		
		return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
	}
}