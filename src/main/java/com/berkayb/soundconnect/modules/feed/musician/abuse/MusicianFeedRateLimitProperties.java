package com.berkayb.soundconnect.modules.feed.musician.abuse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Distributed request budgets for the musician feed HTTP boundary.
 *
 * <p>Protection is disabled by default so a local checkout remains usable
 * without Redis. The production profile enables it, and a startup guard
 * refuses to serve the production feed if it is explicitly disabled.</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.feed.musician.rate-limit")
public class MusicianFeedRateLimitProperties {
    private static final Duration MIN_REFILL_PERIOD = Duration.ofMillis(50);
    private static final Duration MAX_REFILL_PERIOD = Duration.ofMinutes(10);
    private static final Duration MAX_KEY_TTL = Duration.ofHours(24);

    private boolean enabled = false;

    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9:_-]+",
            message = "musician feed rate-limit key prefix contains unsupported characters")
    private String keyPrefix = "soundconnect:musician-feed:rate-limit";

    @Valid
    @NotNull
    private Bucket initial = new Bucket(4, Duration.ofSeconds(15));

    @Valid
    @NotNull
    private Bucket continuation = new Bucket(12, Duration.ofSeconds(2));

    @Valid
    @NotNull
    private Bucket sharedPageBudget = new Bucket(30, Duration.ofSeconds(3));

    @Valid
    @NotNull
    private Bucket telemetry = new Bucket(120, Duration.ofMillis(250));

    @NotNull
    private Duration unavailableRetryAfter = Duration.ofSeconds(5);

    @AssertTrue(message = "musician feed rate-limit bucket policy is outside safe operational bounds")
    public boolean isBucketPolicyValid() {
        if (initial == null || continuation == null || sharedPageBudget == null || telemetry == null) {
            return false;
        }
        if (initial.burstCapacity > 10 || continuation.burstCapacity > 100
                || sharedPageBudget.burstCapacity > 250 || telemetry.burstCapacity > 1_000) {
            return false;
        }
        if (sharedPageBudget.burstCapacity < continuation.burstCapacity
                || !validTiming(initial) || !validTiming(continuation)
                || !validTiming(sharedPageBudget) || !validTiming(telemetry)) {
            return false;
        }
        // The shared budget must constrain a long scroll more strongly than
        // its continuation-only burst bucket.
        return sharedPageBudget.refillPeriod.compareTo(continuation.refillPeriod) >= 0;
    }

    @AssertTrue(message = "musician feed unavailable Retry-After must be between one and sixty seconds")
    public boolean isUnavailableRetryAfterValid() {
        return unavailableRetryAfter != null
                && unavailableRetryAfter.compareTo(Duration.ofSeconds(1)) >= 0
                && unavailableRetryAfter.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    long keyTtlMillis(Bucket bucket) {
        return Math.multiplyExact(Math.multiplyExact(bucket.refillPeriod.toMillis(),
                bucket.burstCapacity), 2L);
    }

    private boolean validTiming(Bucket bucket) {
        if (bucket.refillPeriod == null || bucket.burstCapacity < 1) return false;
        try {
            if (bucket.refillPeriod.compareTo(MIN_REFILL_PERIOD) < 0
                    || bucket.refillPeriod.compareTo(MAX_REFILL_PERIOD) > 0) {
                return false;
            }
            return keyTtlMillis(bucket) <= MAX_KEY_TTL.toMillis();
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    @Getter
    @Setter
    public static class Bucket {
        @Min(1)
        @Max(1_000)
        private int burstCapacity;

        @NotNull
        private Duration refillPeriod;

        public Bucket() {
        }

        Bucket(int burstCapacity, Duration refillPeriod) {
            this.burstCapacity = burstCapacity;
            this.refillPeriod = refillPeriod;
        }
    }
}
