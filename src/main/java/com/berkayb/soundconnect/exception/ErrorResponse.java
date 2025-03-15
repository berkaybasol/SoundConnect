package com.berkayb.soundconnect.exception;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ErrorResponse {
	public Integer code;
	public String message;
	public Boolean success;
	public List<String> details;
	private LocalDateTime timestamp;
	
}