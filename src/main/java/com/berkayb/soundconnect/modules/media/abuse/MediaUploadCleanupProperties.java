package com.berkayb.soundconnect.modules.media.abuse;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "media.upload-cleanup")
public class MediaUploadCleanupProperties {

	private boolean enabled = true;

	@NotNull
	private Duration staleAfter = Duration.ofHours(2);

	@Min(1)
	private int batchSize = 100;

	@Min(1)
	private int workerThreads = 4;

	@Min(1)
	private int queueCapacity = 200;

	private boolean protectedMutableRecoveryEnabled = true;

	@Min(1)
	private int protectedMutableRecoveryBatchSize = 100;

	@Min(1)
	private int protectedMutableRecoveryWorkerThreads = 2;

	@Min(1)
	private int protectedMutableRecoveryQueueCapacity = 200;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getStaleAfter() {
		return staleAfter;
	}

	public void setStaleAfter(Duration staleAfter) {
		this.staleAfter = staleAfter;
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

	public boolean isProtectedMutableRecoveryEnabled() {
		return protectedMutableRecoveryEnabled;
	}

	public void setProtectedMutableRecoveryEnabled(boolean protectedMutableRecoveryEnabled) {
		this.protectedMutableRecoveryEnabled = protectedMutableRecoveryEnabled;
	}

	public int getProtectedMutableRecoveryBatchSize() {
		return protectedMutableRecoveryBatchSize;
	}

	public void setProtectedMutableRecoveryBatchSize(int protectedMutableRecoveryBatchSize) {
		this.protectedMutableRecoveryBatchSize = protectedMutableRecoveryBatchSize;
	}

	public int getProtectedMutableRecoveryWorkerThreads() {
		return protectedMutableRecoveryWorkerThreads;
	}

	public void setProtectedMutableRecoveryWorkerThreads(int protectedMutableRecoveryWorkerThreads) {
		this.protectedMutableRecoveryWorkerThreads = protectedMutableRecoveryWorkerThreads;
	}

	public int getProtectedMutableRecoveryQueueCapacity() {
		return protectedMutableRecoveryQueueCapacity;
	}

	public void setProtectedMutableRecoveryQueueCapacity(int protectedMutableRecoveryQueueCapacity) {
		this.protectedMutableRecoveryQueueCapacity = protectedMutableRecoveryQueueCapacity;
	}

	@AssertTrue(message = "stale upload age must be at least one minute")
	public boolean isStaleAgeValid() {
		return staleAfter != null && staleAfter.compareTo(Duration.ofMinutes(1)) >= 0;
	}
}
