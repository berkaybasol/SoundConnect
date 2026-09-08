package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitProperties;
import com.berkayb.soundconnect.auth.ratelimit.TrustedProxyClientAddressResolver;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class VenueSuggestionRateGuardTest {
    private static final String PREFIX = "soundconnect:{venue-suggestions}:";
    private StringRedisTemplate redis;
    private VenueSuggestionProperties properties;
    private VenueSuggestionRateGuard guard;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        properties = new VenueSuggestionProperties();
        guard = new VenueSuggestionRateGuard(redis, new TrustedProxyClientAddressResolver(List.of()), properties);
        reply(0L);
    }

    @Test
    void permitsZeroAndUsesAtomicThreeWindowScriptWithHashedClusterCompatibleKeys() throws Exception {
        properties.setHourlyLimit(3);
        properties.setDailyLimit(9);
        properties.setGlobalHourlyLimit(120);
        String client = "203.0.113.87";

        assertThatCode(() -> guard.check(request(client))).doesNotThrowAnyException();

        RedisCall call = redisCall();
        String digest = digest(client);
        assertThat(call.keys()).containsExactly(PREFIX + "hour:" + digest, PREFIX + "day:" + digest,
                PREFIX + "global-hour");
        assertThat(call.arguments()).containsExactly("3", "3600", "9", "86400", "120", "3600");
        assertThat(call.keys()).allSatisfy(key -> {
            assertThat(key).contains("{venue-suggestions}");
            assertThat(key).doesNotContain(client);
        });
        assertThat(call.script().getResultType()).isEqualTo(Long.class);
        String script = call.script().getScriptAsString();
        // All windows must be checked before incrementing any counter; exhausted calls consume no quota.
        assertThat(script.indexOf("if retry > 0 then return retry end"))
                .isLessThan(script.indexOf("redis.call('INCR'"));
        assertThat(script).contains("redis.call('TTL'", "redis.call('EXPIRE'", "if count == 1 then");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 73, 86400})
    void positiveRedisResultIsRateLimitedWithExactRetryAfter(long retry) {
        reply(retry);

        RateLimitedException error = catchThrowableOfType(() -> guard.check(request("203.0.113.15")),
                RateLimitedException.class);

        assertThat(error.getErrorType()).isEqualTo(ErrorType.VENUE_SUGGESTION_RATE_LIMITED);
        assertThat(error.getRetryAfterSeconds()).isEqualTo(retry);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1, Long.MIN_VALUE})
    void missingOrInvalidRedisResultsFailClosedWithRetryable503(Long result) {
        reply(result);

        ServiceUnavailableRetryException error = catchThrowableOfType(() -> guard.check(request("203.0.113.15")),
                ServiceUnavailableRetryException.class);

        assertThat(error.getErrorType()).isEqualTo(ErrorType.VENUE_SUGGESTION_UNAVAILABLE);
        assertThat(error.getRetryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void redisOutageFailsClosedAndDoesNotLogOrExposeClientOrConnectionSecrets(CapturedOutput output) {
        String client = "203.0.113.207";
        String secret = "redis://private-user:private-password@internal-only.invalid:6379";
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException(secret + " client=" + client));

        ServiceUnavailableRetryException error = catchThrowableOfType(() -> guard.check(request(client)),
                ServiceUnavailableRetryException.class);

        assertThat(error.getErrorType()).isEqualTo(ErrorType.VENUE_SUGGESTION_UNAVAILABLE);
        assertThat(error.getRetryAfterSeconds()).isEqualTo(30);
        assertThat(error.getMessage()).doesNotContain(client, secret, "private-password");
        assertThat(error.getCause()).isNull();
        assertThat(output.getAll()).doesNotContain(client, secret, "private-password");
    }

    @Test
    void untrustedPeerCannotRotateItsBucketBySpoofingForwardingHeaders() throws Exception {
        MockHttpServletRequest request = request("198.51.100.23");
        request.addHeader("Forwarded", "for=203.0.113.111");
        request.addHeader("X-Forwarded-For", "203.0.113.112");

        guard.check(request);

        assertClientBuckets(redisCall(), "198.51.100.23");
    }

    @Test
    void trustedProxyChainUsesNearestUntrustedClientNotForgedLeftmostAddress() throws Exception {
        guard = new VenueSuggestionRateGuard(redis,
                new TrustedProxyClientAddressResolver(List.of("10.0.0.0/8")), properties);
        MockHttpServletRequest request = request("10.0.0.8");
        request.addHeader("Forwarded", "for=203.0.113.111, for=198.51.100.23, for=10.2.3.4");

        guard.check(request);

        assertClientBuckets(redisCall(), "198.51.100.23");
    }

    @Test
    void configuredXForwardedForUsesTrustedChainAndIgnoresOtherHeader() throws Exception {
        guard = new VenueSuggestionRateGuard(redis,
                new TrustedProxyClientAddressResolver(List.of("10.0.0.0/8"),
                        AuthRateLimitProperties.ForwardedHeader.X_FORWARDED_FOR), properties);
        MockHttpServletRequest request = request("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.111, 198.51.100.23, 10.2.3.4");
        request.addHeader("Forwarded", "for=192.0.2.222");

        guard.check(request);

        assertClientBuckets(redisCall(), "198.51.100.23");
    }

    @Test
    void malformedTrustedForwardingHeaderFallsBackToPeerInsteadOfAnUnboundedBucket() throws Exception {
        guard = new VenueSuggestionRateGuard(redis,
                new TrustedProxyClientAddressResolver(List.of("10.0.0.0/8")), properties);
        MockHttpServletRequest request = request("10.0.0.8");
        request.addHeader("Forwarded", "for=_untrusted-secret-value");

        guard.check(request);

        assertClientBuckets(redisCall(), "10.0.0.8");
    }

    @Test
    void missingRemoteAddressStillUsesBoundedSharedUnknownBucket() throws Exception {
        MockHttpServletRequest request = request(null);

        guard.check(request);

        assertClientBuckets(redisCall(), "unknown");
    }

    private void reply(Long result) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(result);
    }

    private RedisCall redisCall() {
        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(script.capture(), keys.capture(), arguments.capture());
        return new RedisCall(script.getValue(), keys.getValue(), arguments.getValue());
    }

    private static void assertClientBuckets(RedisCall call, String client) throws Exception {
        assertThat(call.keys()).containsExactly(PREFIX + "hour:" + digest(client),
                PREFIX + "day:" + digest(client), PREFIX + "global-hour");
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/venue-suggestions");
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private record RedisCall(RedisScript<Long> script, List<String> keys, Object[] arguments) { }
}
