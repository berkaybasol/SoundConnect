package com.berkayb.soundconnect.shared.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
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
	private List<String> details;
	private LocalDateTime timestamp;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String businessName;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String rejectionReason;
	
}
