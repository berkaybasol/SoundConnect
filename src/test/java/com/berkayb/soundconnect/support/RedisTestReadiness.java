package com.berkayb.soundconnect.support;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

public final class RedisTestReadiness {
    private RedisTestReadiness() {}

    /** Complete the shared Lettuce handshake before exercising the behavior under test. */
    public static void awaitPing(RedisConnectionFactory factory) {
        await().alias("disposable Redis PONG").atMost(Duration.ofSeconds(5))
                .ignoreExceptionsMatching(error -> error instanceof RedisConnectionFailureException
                        || error instanceof QueryTimeoutException)
                .until(() -> {
                    try (var connection = factory.getConnection()) {
                        return "PONG".equals(connection.ping());
                    }
                });
    }
}
