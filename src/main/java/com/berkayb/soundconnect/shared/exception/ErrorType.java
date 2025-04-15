package com.berkayb.soundconnect.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorType {
	
	// USER
	USER_NOT_FOUND(1001,"User not found",HttpStatus.NOT_FOUND),
	USER_ALREADY_EXISTS(1002,"User already exists",HttpStatus.CONFLICT),
	EMAIL_ALREADY_EXISTS(1003,"Email already exists",HttpStatus.CONFLICT),
	
	
	// AUTH
	// yanlis kullanici adi veya sifre
	INVALID_CREDENTIALS(1100,"Invalid username or password",HttpStatus.CONFLICT),
	// yetki yok yani authorize olmamis daha.
	UNAUTHORIZED(1101,"You are not authorized",HttpStatus.UNAUTHORIZED),
	// erismeye calistigi bolume permisson atanmamis
	FORBIDDEN_ACCESS(1102,"You don't have permission to access this resource",HttpStatus.FORBIDDEN),
	// tokenin suresi dolmus.
	TOKEN_EXPIRED(1103,"JWT token has expired",HttpStatus.UNAUTHORIZED),
	
	// FOLLOW
	FOLLOW_RELATION_NOT_FOUND(1200, "Follow relation not found",HttpStatus.NOT_FOUND),
	ALREADY_FOLLOWING(1201,"You are already following this user",HttpStatus.CONFLICT),
	CANNOT_FOLLOW_SELF(1202,"You cannot follow yourself",HttpStatus.BAD_REQUEST),
	
	// INSTRUMENT
	INSTRUMENT_NOT_FOUND(1300,"Instrument not found",HttpStatus.NOT_FOUND),
	
	// VALID
	VALIDATION_ERROR(4000,"Validation failed", HttpStatus.BAD_REQUEST),
	
	// ROLE-PERMISSIONS
	ROLE_NOT_FOUND(5001,"Role not found",HttpStatus.NOT_FOUND),
	ROLE_ALREADY_EXISTS(5002,"Role already exists",HttpStatus.BAD_REQUEST),
	PERMISSION_NOT_FOUND(5003, "Permission not found", HttpStatus.NOT_FOUND),
	PERMISSION_ALREADY_EXISTS(5004, "Permission already exists", HttpStatus.BAD_REQUEST),
	
	
	
	
	
	// GENEL
	INTERNAL_ERROR(9999,"Internal error",HttpStatus.INTERNAL_SERVER_ERROR);
	
	
	
	private final int code;
	private final String message;
	private final HttpStatus httpStatus;
	
	ErrorType(int code, String message, HttpStatus httpStatus) {
		this.code = code;
		this.message = message;
		this.httpStatus = httpStatus;
	}
}