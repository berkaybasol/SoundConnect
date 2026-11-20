package com.berkayb.soundconnect.shared.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

import java.util.List;

/** Bounded broker-native delay lanes; one fixed TTL per queue avoids FIFO TTL blocking. */
public final class MailRetryTopology {
    public static final long MAX_DELAY_MS = 86_400_000L;
    private static final List<Long> DELAYS = List.of(100L, 1_000L, 3_000L, 10_000L, 30_000L,
            60_000L, 300_000L, 1_800_000L, 7_200_000L, 21_600_000L, MAX_DELAY_MS);

    private MailRetryTopology() { }

    public static List<Long> delays() { return DELAYS; }

    public static long bucketDelayMs(long minimumDelayMs) {
        if (minimumDelayMs < 1 || minimumDelayMs > MAX_DELAY_MS) {
            throw new IllegalArgumentException("Retry delay must be between 1ms and 24h");
        }
        return DELAYS.stream().filter(delay -> delay >= minimumDelayMs).findFirst().orElseThrow();
    }

    public static String queueName(String mainQueue, long minimumDelayMs) {
        validateMainQueue(mainQueue);
        return mainQueue + ".retry.v2." + bucketDelayMs(minimumDelayMs);
    }

    public static void validateMainQueue(String mainQueue) {
        if (mainQueue == null || !mainQueue.matches("[A-Za-z0-9._-]{1,180}")) {
            throw new IllegalArgumentException("Invalid mail queue name for retry topology");
        }
    }

    public static Queue queue(String mainQueue, long delayMs, String exchange, String routingKey) {
        return QueueBuilder.durable(queueName(mainQueue, delayMs))
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-message-ttl", bucketDelayMs(delayMs))
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-max-length", 1_000)
                .withArgument("x-max-length-bytes", 4_194_304L)
                .build();
    }
}
