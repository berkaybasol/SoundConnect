package com.berkayb.soundconnect.modules.media.image;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "media.image-variants")
public class MediaImageVariantProperties {

	@NotBlank
	private String publicCacheControl =
			"public, max-age=300, s-maxage=3600, stale-while-revalidate=60";

	@Min(320)
	@Max(2048)
	private int thumbnailMaxDimension = 960;

	@Min(1_000_000)
	private long maxSourcePixels = 60_000_000L;

	@DecimalMin("0.5")
	@DecimalMax("0.95")
	private double jpegQuality = 0.82d;

	@Min(5)
	@Max(120)
	private int processTimeoutSeconds = 30;

	private final Backfill backfill = new Backfill();

	@Data
	public static class Backfill {
		private boolean enabled = true;

		@Min(1)
		@Max(500)
		private int batchSize = 25;

		@Min(1)
		@Max(8)
		private int workerThreads = 2;

		@Min(1)
		@Max(2_000)
		private int queueCapacity = 100;

		private Duration initialDelay = Duration.ofMinutes(2);

		private Duration fixedDelay = Duration.ofMinutes(10);
	}
}
