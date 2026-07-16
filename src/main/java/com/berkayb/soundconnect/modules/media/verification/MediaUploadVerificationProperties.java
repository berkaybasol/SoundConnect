package com.berkayb.soundconnect.modules.media.verification;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "media.upload-verification")
public class MediaUploadVerificationProperties {

	private boolean enabled = true;

	@Min(1)
	private int workerThreads = 4;

	@Min(1)
	private int queueCapacity = 100;

	@Min(1)
	private int recoveryBatchSize = 50;

	@Min(1_000)
	private long recoveryFixedDelayMs = 30_000L;

	/**
	 * Bounds the servlet wait while the durable worker keeps running. A short
	 * timeout prevents slow object storage from consuming the HTTP thread pool.
	 */
	@NotNull
	private Duration requestWaitTimeout = Duration.ofSeconds(4);

	/**
	 * Cross-node crash-recovery lease. It must comfortably exceed the expected
	 * HEAD/COPY/signature-validation path; expiry never authorizes a stale worker
	 * to finalize because the attempt token is checked independently.
	 */
	@NotNull
	private Duration verificationLease = Duration.ofMinutes(4);

	/** Non-renewable maximum wall-clock authority of one claimed worker. */
	@NotNull
	private Duration attemptHardTimeout = Duration.ofMinutes(5);

	/** Exact upper bound configured on each synchronous S3 API call. */
	@NotNull
	private Duration storageCallTimeout = Duration.ofSeconds(30);
}
