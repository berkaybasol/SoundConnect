package com.berkayb.soundconnect.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class RateLimitedException extends SoundConnectException {

	private final long retryAfterSeconds;

	public RateLimitedException(ErrorType errorType, long retryAfterSeconds) {
		super(errorType);
		if (errorType.getHttpStatus() != HttpStatus.TOO_MANY_REQUESTS) {
			throw new IllegalArgumentException("Rate-limited errors must use HTTP 429");
		}
		this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
	}
}
