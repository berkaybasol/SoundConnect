package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.musician-profile.calendar-rate-limit")
public class MusicianCalendarRateLimitProperties {
	private boolean enabled = true;
	@NotBlank
	private String keyPrefix = "soundconnect:musician-profile:calendar-rate-limit";
	@Min(1) @Max(10)
	private int burstCapacity = 3;
	@NotNull
	private Duration refillPeriod = Duration.ofSeconds(10);

	@AssertTrue(message = "calendar rate-limit refill period must be between five seconds and one minute")
	public boolean isRefillPeriodValid() {
		return refillPeriod != null && refillPeriod.compareTo(Duration.ofSeconds(5)) >= 0
				&& refillPeriod.compareTo(Duration.ofMinutes(1)) <= 0;
	}
}
