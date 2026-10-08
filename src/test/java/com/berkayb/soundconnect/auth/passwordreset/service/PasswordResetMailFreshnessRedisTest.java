package com.berkayb.soundconnect.auth.passwordreset.service;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ForgotPasswordRequestDto;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationMailDelivery;
import com.berkayb.soundconnect.shared.config.RedisConfig;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.consumer.MailJobConsumer;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.helper.MailContentBuilder;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.mail.producer.MailRetryPublisher;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Redis ordering; provider double and mocked account repository, not HTTP acceptance. */
@Testcontainers
@DataRedisTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.autoconfigure.exclude=", "spring.data.redis.client-type=lettuce",
        "otp.ttl.minutes=1", "otp.length=6", "otp.max-attempt=5", "otp.resend.cooldown.seconds=1",
        "mail.maxRedeliveries=2", "mail.retry.delaysMs=10,20"
})
@Import({OtpService.class, RedisConfig.class, PasswordResetService.class, PasswordResetMailService.class,
        MailJobConsumer.class, MailJobHelper.class})
class PasswordResetMailFreshnessRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withLabel("soundconnect.task", "BIL-012-reset-freshness-test").withExposedPorts(6379);

    @DynamicPropertySource static void redisProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.ssl.enabled", () -> false);
    }

    @Autowired PasswordResetService resetService;
    @Autowired MailJobConsumer consumer;
    @SpyBean OtpService otp;
    @SpyBean(name = "redisTemplate") RedisTemplate<String, String> redis;
    @SpyBean MailJobHelper helper;
    @MockBean UserRepository users;
    @MockBean jakarta.persistence.EntityManager entityManager;
    @MockBean PasswordEncoder encoder;
    @MockBean AuthAccountRateLimitGuard accountGuard;
    @MockBean PublicProfileResolverService profiles;
    @MockBean MailProducer producer;
    @MockBean MailSenderClient sender;
    @MockBean MailRetryPublisher retry;
    @MockBean MailContentBuilder content;
    @MockBean NotificationMailDelivery notification;

    private final List<MailSendRequest> queued = new CopyOnWriteArrayList<>();
    private final List<OtpService.OtpIssueClaim> claims = new CopyOnWriteArrayList<>();
    private Channel channel;
    private static final String EMAIL = "owned-bil012@example.invalid";

    @BeforeEach void prepare() {
        // Only the disposable container owned by this class is flushed.
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        queued.clear(); claims.clear(); channel = mock(Channel.class);
        when(users.findByEmail(anyString())).thenAnswer(invocation -> {
            User user = new User(); user.setId(UUID.randomUUID()); user.setEmail(invocation.getArgument(0));
            user.setUsername("owned-bil012"); user.setProvider(AuthProvider.LOCAL);
            return Optional.of(user);
        });
        when(content.buildPasswordResetMail(anyString(), anyInt())).thenReturn("<html>private reset</html>");
        doAnswer(invocation -> { queued.add(invocation.getArgument(0)); return null; }).when(producer).send(any());
        doAnswer(invocation -> {
            OtpService.OtpIssueClaim claim = (OtpService.OtpIssueClaim) invocation.callRealMethod();
            if (claim.acquired()) claims.add(claim);
            return claim;
        }).when(otp).acquirePasswordResetOtp(anyString());
    }

    private MailSendRequest issue(String recipient) {
        resetService.requestPasswordReset(new ForgotPasswordRequestDto(recipient));
        return queued.getLast();
    }

    private MailSendRequest reissue(String recipient) {
        await().atMost(Duration.ofSeconds(3)).until(() -> redis.keys("soundconnect:otp:*:password-reset:resend-guard").isEmpty());
        return issue(recipient);
    }

    private void deliver(MailSendRequest job) { consumer.listenMailJobs(job, 71L, Map.of(), channel); }

    private MailSendRequest replace(MailSendRequest job, String recipient, Map<String, Object> params) {
        return new MailSendRequest(recipient, job.subject(), job.htmlBody(), job.textBody(), job.kind(), params);
    }

    @Test void twoRequestsDelayedOldMailIsDiscardedAndCurrentMailRemainsUsable() throws Exception {
        MailSendRequest old = issue(EMAIL);
        MailSendRequest current = reissue(EMAIL);
        deliver(old);
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
        assertThat(mockingDetails(retry).getInvocations().size()).isZero();
        verify(channel).basicAck(71L, false);
        deliver(current);
        assertThat(mockingDetails(sender).getInvocations().size()).isEqualTo(1);
        assertThat(otp.verifyPasswordResetOtp(EMAIL, claims.getLast().code())).isTrue();
    }

    @Test void consumedClaimIsDiscardedWithoutProviderOrRetry() throws Exception {
        MailSendRequest job = issue(EMAIL);
        assertThat(otp.verifyPasswordResetOtp(EMAIL, claims.getLast().code())).isTrue();
        deliver(job);
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
        assertThat(mockingDetails(retry).getInvocations().size()).isZero();
        verify(channel).basicAck(71L, false);
    }

    @Test void expiredClaimIsDiscardedWithoutProviderOrRetry() throws Exception {
        MailSendRequest job = issue(EMAIL);
        String key = redis.keys("soundconnect:otp:*:password-reset:code").iterator().next();
        redis.expire(key, Duration.ofMillis(5)); // Technical expiry test, not natural/API acceptance.
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        deliver(job);
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
        assertThat(mockingDetails(retry).getInvocations().size()).isZero();
        verify(channel).basicAck(71L, false);
    }

    @Test void wrongRecipientAndMissingOrMalformedIdentityFailClosed() throws Exception {
        MailSendRequest job = issue(EMAIL);
        List<Map<String, Object>> malformed = List.of(Map.of(), Map.of("requestId", "not-a-generation"),
                Map.of("requestId", UUID.randomUUID().toString(), "claimRecipient", EMAIL, "expiresAtEpochMillis", 1.5));
        for (Map<String, Object> params : malformed) deliver(replace(job, EMAIL, params));
        deliver(replace(job, "sibling@example.invalid", job.params()));
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
        assertThat(mockingDetails(retry).getInvocations().size()).isZero();
        verify(channel, times(4)).basicAck(71L, false);
        // These discard decisions neither consume nor mutate the real claim.
        assertThat(otp.verifyPasswordResetOtp(EMAIL, claims.getLast().code())).isTrue();
    }

    @Test void siblingMailRemainsIndependentOfSupersededRecipient() {
        MailSendRequest old = issue(EMAIL);
        MailSendRequest sibling = issue("sibling@example.invalid");
        reissue(EMAIL);
        deliver(old); deliver(sibling);
        assertThat(mockingDetails(sender).getInvocations().stream().filter(i -> EMAIL.equals(i.getArgument(0))).count()).isZero();
        assertThat(mockingDetails(sender).getInvocations().stream().filter(i -> "sibling@example.invalid".equals(i.getArgument(0))).count()).isEqualTo(1);
    }

    @Test void providerRetryRechecksFreshnessAfterSupersede() throws Exception {
        MailSendRequest old = issue(EMAIL);
        doThrow(new ResourceAccessException("isolated provider outage"))
                .when(sender).send(anyString(), anyString(), anyString(), anyString());
        deliver(old);
        assertThat(mockingDetails(retry).getInvocations().size()).isEqualTo(1);
        assertThat((Integer) mockingDetails(retry).getInvocations().iterator().next().getArgument(2)).isEqualTo(1);
        reissue(EMAIL);
        clearInvocations(sender, retry, channel);
        consumer.listenMailJobs(old, 72L, Map.of(MailJobHelper.RETRY_ATTEMPT_HEADER, 1), channel);
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
        assertThat(mockingDetails(retry).getInvocations().size()).isZero();
        verify(channel).basicAck(72L, false);
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void unavailableAuthoritativeStoreRetriesAndExhaustsWithoutSending() throws Exception {
        MailSendRequest job = issue(EMAIL);
        doThrow(new RedisConnectionFailureException("isolated authoritative read failure"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        deliver(job);
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
        assertThat(mockingDetails(retry).getInvocations().size()).isEqualTo(1);
        assertThat((Integer) mockingDetails(retry).getInvocations().iterator().next().getArgument(2)).isEqualTo(1);
        consumer.listenMailJobs(job, 72L, Map.of(MailJobHelper.RETRY_ATTEMPT_HEADER, 2), channel);
        verify(channel).basicReject(72L, false);
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
    }

    @Test void issueBeforeAuthorizationBarrierDiscardsOldMail() throws Exception {
        MailSendRequest old = issue(EMAIL);
        CountDownLatch beforeAuthorization = new CountDownLatch(1), proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object locked = invocation.callRealMethod(); beforeAuthorization.countDown();
            assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue(); return locked;
        }).when(helper).acquireLock(anyString(), any());
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            Future<?> delivery = workers.submit(() -> deliver(old));
            assertThat(beforeAuthorization.await(5, TimeUnit.SECONDS)).isTrue();
            reissue(EMAIL); proceed.countDown(); delivery.get(5, TimeUnit.SECONDS);
        } finally { proceed.countDown(); }
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
        assertThat(mockingDetails(retry).getInvocations().size()).isZero();
    }

    @Test void consumeBeforeAuthorizationBarrierDiscardsOldMail() throws Exception {
        MailSendRequest job = issue(EMAIL);
        CountDownLatch beforeAuthorization = new CountDownLatch(1), proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object locked = invocation.callRealMethod(); beforeAuthorization.countDown();
            assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue(); return locked;
        }).when(helper).acquireLock(anyString(), any());
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            Future<?> delivery = workers.submit(() -> deliver(job));
            assertThat(beforeAuthorization.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(otp.verifyPasswordResetOtp(EMAIL, claims.getLast().code())).isTrue();
            proceed.countDown(); delivery.get(5, TimeUnit.SECONDS);
        } finally { proceed.countDown(); }
        assertThat(mockingDetails(sender).getInvocations().size()).isZero();
        assertThat(mockingDetails(retry).getInvocations().size()).isZero();
    }

    @Test void providerAlreadyStartedCanFinishAfterIssueWithoutCancellingNewClaim() throws Exception {
        MailSendRequest old = issue(EMAIL);
        CountDownLatch providerStarted = new CountDownLatch(1), providerFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            providerStarted.countDown(); assertThat(providerFinish.await(5, TimeUnit.SECONDS)).isTrue(); return null;
        }).when(sender).send(anyString(), anyString(), anyString(), anyString());
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            Future<?> delivery = workers.submit(() -> deliver(old));
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            reissue(EMAIL); providerFinish.countDown(); delivery.get(5, TimeUnit.SECONDS);
        } finally { providerFinish.countDown(); }
        assertThat(mockingDetails(sender).getInvocations().size()).isEqualTo(1);
        assertThat(otp.verifyPasswordResetOtp(EMAIL, claims.getLast().code())).isTrue();
    }
}
