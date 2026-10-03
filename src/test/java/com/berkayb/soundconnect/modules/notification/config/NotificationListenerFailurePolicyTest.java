package com.berkayb.soundconnect.modules.notification.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class NotificationListenerFailurePolicyTest {
    @Test void processingFailuresHaveFourAttemptsAndBoundedExponentialWaits() {
        var waits = new ArrayList<Long>();
        var template = recordingTemplate(waits);
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> template.execute(context -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("temporary processing failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(4);
        assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L);
    }

    @Test void transientDatabaseFailureCanRecoverBeforeTheBudgetIsExhausted() {
        var waits = new ArrayList<Long>();
        var template = recordingTemplate(waits);
        var attempts = new AtomicInteger();
        String result = template.execute(context -> {
            if (attempts.incrementAndGet() < 3) throw new TransientDataAccessResourceException("database unavailable");
            return "persisted";
        });
        assertThat(result).isEqualTo("persisted");
        assertThat(attempts).hasValue(3);
        assertThat(waits).containsExactly(1_000L, 2_000L);
    }

    @Test void connectionAcquisitionFailureUsesAllBoundedAttemptsDespiteItsNonTransientBaseClass() {
        var waits = new ArrayList<Long>();
        var template = recordingTemplate(waits);
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> template.execute(context -> {
            attempts.incrementAndGet();
            throw new org.springframework.jdbc.CannotGetJdbcConnectionException("connection unavailable",
                    new java.sql.SQLTransientConnectionException("network unavailable"));
        })).isInstanceOf(org.springframework.jdbc.CannotGetJdbcConnectionException.class);
        assertThat(attempts).hasValue(4);
        assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L);
    }
    @Test void permanentFailuresInsideListenerWrappersAreNeverRetried() {
        for (RuntimeException cause : List.of(
                new IllegalArgumentException("payload depth"),
                new DataIntegrityViolationException("constraint"),
                new AmqpRejectAndDontRequeueException("explicit rejection"),
                new org.springframework.amqp.support.converter.MessageConversionException("bad JSON"))) {
            var waits = new ArrayList<Long>();
            var template = recordingTemplate(waits);
            var attempts = new AtomicInteger();
            var wrapper = new ListenerExecutionFailedException("wrapper", cause, message("private"));
            assertThatThrownBy(() -> template.execute(context -> {
                attempts.incrementAndGet();
                throw wrapper;
            })).isSameAs(wrapper);
            assertThat(attempts).hasValue(1);
            assertThat(waits).isEmpty();
        }
    }

    @Test void recovererAndErrorHandlerDoNotLogPrivateBodyHeadersOrExceptionMessages() {
        String privateValue = "PRIVATE_DM_EMAIL_TOKEN_MUST_NOT_BE_LOGGED";
        var original = message(privateValue);
        original.getMessageProperties().setHeader("eventId", privateValue);
        original.getMessageProperties().setCorrelationId(privateValue);
        var failure = new ListenerExecutionFailedException(privateValue,
                new IllegalStateException(privateValue), original);
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationListenerFailurePolicy.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> NotificationListenerFailurePolicy.rejectExhausted(original, failure))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasNoCause().hasMessageNotContaining(privateValue);
            assertThatThrownBy(() -> NotificationListenerFailurePolicy.rejectSafely(failure))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasNoCause().hasMessageNotContaining(privateValue);
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("IllegalStateException").doesNotContain(privateValue);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(new String(original.getBody(), StandardCharsets.UTF_8)).isEqualTo(privateValue);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static RetryTemplate recordingTemplate(List<Long> waits) {
        var template = NotificationListenerFailurePolicy.retryTemplate();
        var backoff = (ExponentialBackOffPolicy) ReflectionTestUtils.getField(template, "backOffPolicy");
        assertThat(backoff).isNotNull();
        backoff.setSleeper(waits::add);
        return template;
    }
    private static Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
