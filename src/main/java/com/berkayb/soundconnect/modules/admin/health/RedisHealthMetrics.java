package com.berkayb.soundconnect.modules.admin.health;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisServerAsyncCommands;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Optional read-only INFO projection inside the existing bounded health probe.
 * Uses the existing Lettuce connection/event loop; owns no client, thread or scheduler.
 */
@Component
@ConditionalOnProperty(prefix = "app.telemetry.metrics", name = "enabled", havingValue = "true")
public final class RedisHealthMetrics {
    private static final long MAX_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int MAX_SECTION_CHARS = 65_536;
    private static final Map<String, String> KEYS = Map.of(
            "used_memory", "redisUsedMemoryBytes", "maxmemory", "redisMaxMemoryBytes",
            "evicted_keys", "redisEvictedKeys", "connected_clients", "redisConnectedClients",
            "blocked_clients", "redisBlockedClients", "rejected_connections", "redisRejectedConnections",
            "total_connections_received", "redisTotalConnectionsReceived", "mem_fragmentation_ratio", "redisMemoryFragmentationRatio");
    private final RedisConnectionFactory connections;

    public RedisHealthMetrics(RedisConnectionFactory connections) { this.connections = connections; }

    Map<String, Double> read(long remainingProbeNanos) {
        long started = System.nanoTime();
        long budget = Math.min(MAX_WAIT_NANOS, remainingProbeNanos);
        if (budget <= 0 || Thread.currentThread().isInterrupted()) return Map.of();
        long deadline = started + budget;
        var futures = new ArrayList<RedisFuture<String>>(3);
        try (RedisConnection connection = connections.getConnection()) {
            // Do not mutate a shared native connection's timeout. Other drivers
            // keep the old health result and simply omit this optional detail.
            if (!(connection.getNativeConnection() instanceof RedisServerAsyncCommands<?, ?> commands)
                    || deadline - System.nanoTime() <= 0 || Thread.currentThread().isInterrupted()) return Map.of();
            futures.add(commands.info("memory"));
            futures.add(commands.info("stats"));
            futures.add(commands.info("clients"));
            var result = new LinkedHashMap<String, Double>();
            for (RedisFuture<String> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return Map.of();
                String section = future.get(remaining, TimeUnit.NANOSECONDS);
                if (section == null || section.length() > MAX_SECTION_CHARS) return Map.of();
                result.putAll(project(section));
            }
            result.put("redisInfoLatencyMillis", (System.nanoTime() - started) / 1_000_000d);
            return Map.copyOf(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (RuntimeException | ExecutionException | TimeoutException unavailable) {
            // INFO ACL/timeout/unsupported driver must not override contributor health.
            // No raw response, connection address or exception content is logged.
            return Map.of();
        } finally {
            for (RedisFuture<String> future : futures) {
                try {
                    if (!future.isDone()) future.cancel(true);
                } catch (RuntimeException ignored) {
                    // Optional observation cleanup must not change the health result.
                }
            }
        }
    }

    static Map<String, Double> project(String section) {
        if (section == null || section.length() > MAX_SECTION_CHARS) return Map.of();
        var result = new LinkedHashMap<String, Double>();
        var seen = new HashSet<String>();
        for (String line : section.split("\\r?\\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            String key = line.substring(0, separator);
            String target = KEYS.get(key);
            if (target == null) continue;
            if (!seen.add(key)) { result.remove(target); continue; }
            String raw = line.substring(separator + 1);
            String format = "mem_fragmentation_ratio".equals(key) ? "(?:0|[1-9][0-9]{0,19})(?:[.][0-9]{1,20})?" : "(?:0|[1-9][0-9]{0,19})";
            if (!raw.matches(format)) continue;
            double value = Double.parseDouble(raw);
            if (Double.isFinite(value) && value >= 0) result.put(target, value);
        }
        return Map.copyOf(result);
    }
}
