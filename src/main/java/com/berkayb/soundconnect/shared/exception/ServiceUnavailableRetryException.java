package com.berkayb.soundconnect.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A temporary 503 response with explicit client retry guidance. */
@Getter
public class ServiceUnavailableRetryException extends SoundConnectException {

	private final long retryAfterSeconds;

	public ServiceUnavailableRetryException(ErrorType errorType, long retryAfterSeconds) {
		super(errorType);
		if (errorType.getHttpStatus() != HttpStatus.SERVICE_UNAVAILABLE) {
			throw new IllegalArgumentException("Retryable unavailable errors must use HTTP 503");
		}
		this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
	}
}
