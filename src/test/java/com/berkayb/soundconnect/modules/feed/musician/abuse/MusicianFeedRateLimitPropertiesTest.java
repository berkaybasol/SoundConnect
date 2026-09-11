package com.berkayb.soundconnect.modules.feed.musician.abuse;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedRateLimitPropertiesTest {
    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void localDefaultsAreDisabledButOperationallyValid() {
        MusicianFeedRateLimitProperties properties = new MusicianFeedRateLimitProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.keyTtlMillis(properties.getInitial())).isEqualTo(120_000L);
        assertThat(properties.keyTtlMillis(properties.getContinuation())).isEqualTo(48_000L);
        assertThat(properties.keyTtlMillis(properties.getSharedPageBudget())).isEqualTo(180_000L);
        assertThat(properties.keyTtlMillis(properties.getTelemetry())).isEqualTo(60_000L);
    }

    @Test
    void rejectsAnInitialBurstThatIsNoLongerStrict() {
        MusicianFeedRateLimitProperties properties = new MusicianFeedRateLimitProperties();
        properties.getInitial().setBurstCapacity(11);

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("bucketPolicyValid"));
    }

    @Test
    void rejectsSharedBudgetThatCannotContainContinuationBurstsOrSustainedTraffic() {
        MusicianFeedRateLimitProperties properties = new MusicianFeedRateLimitProperties();
        properties.getSharedPageBudget().setBurstCapacity(5);

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("bucketPolicyValid"));

        properties = new MusicianFeedRateLimitProperties();
        properties.getSharedPageBudget().setRefillPeriod(Duration.ofSeconds(1));
        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("bucketPolicyValid"));
    }

    @Test
    void rejectsUnboundedKeyLifetimeAndInvalidRetryAdvice() {
        MusicianFeedRateLimitProperties properties = new MusicianFeedRateLimitProperties();
        properties.getTelemetry().setBurstCapacity(1_000);
        properties.getTelemetry().setRefillPeriod(Duration.ofMinutes(10));
        properties.setUnavailableRetryAfter(Duration.ofMillis(999));

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("bucketPolicyValid", "unavailableRetryAfterValid");
    }

    @Test
    void rejectsPrefixThatCouldBreakThePerUserRedisClusterHashTag() {
        MusicianFeedRateLimitProperties properties = new MusicianFeedRateLimitProperties();
        properties.setKeyPrefix("feed:{shared}");

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("keyPrefix"));
    }
}
