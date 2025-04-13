package com.berkayb.soundconnect.shared.exception;

import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ErrorResponse {
	private Integer code;
	private String message;
	private HttpStatus httpStatus;
	private String path;
	public List<String> details;
	private LocalDateTime timestamp;
	
}