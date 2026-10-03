package com.berkayb.soundconnect.modules.notification.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Four listener attempts, with bounded 1/2/4 second waits on its consumer thread.
 * Unacked broker delivery survives a process failure; an interrupted process does
 * not persist the in-memory retry count. Permanent/repeated failures reach the
 * existing durable DLQ rather than a hot requeue or an acknowledged discard.
 */
final class NotificationListenerFailurePolicy {
    private static final Logger log = LoggerFactory.getLogger(NotificationListenerFailurePolicy.class);
    static final int MAX_ATTEMPTS = 4;

    private NotificationListenerFailurePolicy() { }

    static RetryTemplate retryTemplate() {
        var template = new RetryTemplate();
        template.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS) {
            @Override public boolean canRetry(RetryContext context) {
                return !permanent(context.getLastThrowable()) && super.canRetry(context);
            }
        });
        var backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1_000);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(4_000);
        template.setBackOffPolicy(backoff);
        return template;
    }

    private static boolean permanent(Throwable failure) {
        // ListenerExecutionFailedException wraps the application exception. A
        // bounded scan also handles a malformed custom exception cause cycle.
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
            if (failure instanceof AmqpRejectAndDontRequeueException
                    || failure instanceof IllegalArgumentException
                    || failure instanceof DataIntegrityViolationException
                    || failure instanceof InvalidDataAccessApiUsageException
                    || failure instanceof InvalidDataAccessResourceUsageException
                    || failure instanceof org.springframework.amqp.support.converter.MessageConversionException
                    || failure instanceof org.springframework.messaging.converter.MessageConversionException) return true;
        }
        return false;
    }

    static void rejectExhausted(Message message, Throwable failure) {
        // The framework's default recoverer logs failedMessage.toString(). Its
        // body and the exception message may contain private notification data.
        log.warn("Notification delivery moved to DLQ after bounded processing. exceptionType={}", safeType(failure));
        throw rejection();
    }

    static void rejectSafely(Throwable failure) {
        // Conversion errors can occur before the retry advice. Do not attach the
        // original cause: the container must never log its body/message/stack.
        log.warn("Notification delivery rejected to DLQ. exceptionType={}", safeType(failure));
        throw rejection();
    }

    private static AmqpRejectAndDontRequeueException rejection() {
        return new AmqpRejectAndDontRequeueException("Notification delivery rejected; inspect protected DLQ");
    }

    private static String safeType(Throwable failure) {
        if (failure == null) return "UnknownFailure";
        for (int depth = 0; failure.getCause() != null && depth < 32; depth++) failure = failure.getCause();
        return failure.getClass().getSimpleName();
    }
}
