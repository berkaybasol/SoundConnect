package com.berkayb.soundconnect.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorType {
	
	USER_NOT_FOUND(1001,"User not found",HttpStatus.NOT_FOUND),
	VALIDATION_ERROR(400,"Validation error",HttpStatus.BAD_REQUEST),
	INTERNAL_SERVER_ERROR(500, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
	
	
	private final int code;
	private final String message;
	private final HttpStatus httpStatus;
}