package com.berkayb.soundconnect.modules.media.deletion;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "media.deletion")
public class MediaDeletionProperties {

	private boolean enabled = true;

	@Min(1)
	private int batchSize = 100;

	@Min(1)
	private int workerThreads = 2;

	@Min(1)
	private int queueCapacity = 100;

	/** Covers the thumbnail producer's native process and final object upload. */
	@NotNull
	private Duration publicImageProducerGrace = Duration.ofMinutes(5);

	/**
	 * Covers the complete multi-rendition FFmpeg workflow, not a single child
	 * process. Keep this above media.transcode.processing-timeout-hours.
	 */
	@NotNull
	private Duration publicVideoProducerGrace = Duration.ofHours(14);

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public int getWorkerThreads() {
		return workerThreads;
	}

	public void setWorkerThreads(int workerThreads) {
		this.workerThreads = workerThreads;
	}

	public int getQueueCapacity() {
		return queueCapacity;
	}

	public void setQueueCapacity(int queueCapacity) {
		this.queueCapacity = queueCapacity;
	}

	public Duration getPublicImageProducerGrace() {
		return publicImageProducerGrace;
	}

	public void setPublicImageProducerGrace(Duration publicImageProducerGrace) {
		this.publicImageProducerGrace = publicImageProducerGrace;
	}

	public Duration getPublicVideoProducerGrace() {
		return publicVideoProducerGrace;
	}

	public void setPublicVideoProducerGrace(Duration publicVideoProducerGrace) {
		this.publicVideoProducerGrace = publicVideoProducerGrace;
	}

	@AssertTrue(message = "media deletion producer grace windows are outside their safe bounds")
	public boolean areProducerGraceWindowsValid() {
		return publicImageProducerGrace != null
				&& publicImageProducerGrace.compareTo(Duration.ofMinutes(5)) >= 0
				&& publicImageProducerGrace.compareTo(Duration.ofHours(1)) <= 0
				&& publicVideoProducerGrace != null
				&& publicVideoProducerGrace.compareTo(Duration.ofHours(1)) >= 0
				&& publicVideoProducerGrace.compareTo(Duration.ofDays(7)) <= 0;
	}
}
