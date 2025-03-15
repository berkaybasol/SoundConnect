package com.berkayb.soundconnect.exception;

import com.berkayb.soundconnect.exception.ErrorResponse;
import com.berkayb.soundconnect.exception.ErrorType;
import com.berkayb.soundconnect.exception.SoundConnectException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
	
	private ResponseEntity<ErrorResponse> createErrorResponse(ErrorType errorType, List<String> details) {
		log.error("Error occurred: {}, Details: {}", errorType.getMessage(), details);
		
		return ResponseEntity.status(errorType.getHttpStatus()).body(
				ErrorResponse.builder()
				             .code(errorType.getCode())
				             .message(errorType.getMessage())
				             .success(false)
				             .details(details)
				             .timestamp(LocalDateTime.now())
				             .build()
		);
	}
	
	@ExceptionHandler(SoundConnectException.class)
	public ResponseEntity<ErrorResponse> handleSoundConnectException(SoundConnectException e) {
		return createErrorResponse(e.getErrorType(), e.getDetails());
	}
	
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
		List<String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
		                            .map(fieldError -> fieldError.getField() + " Validation Error: " + fieldError.getDefaultMessage())
		                            .collect(Collectors.toList());
		
		return createErrorResponse(ErrorType.VALIDATION_ERROR, fieldErrors);
	}
	
	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException e) {
		return createErrorResponse(ErrorType.INTERNAL_SERVER_ERROR, List.of(e.getMessage()));
	}
}