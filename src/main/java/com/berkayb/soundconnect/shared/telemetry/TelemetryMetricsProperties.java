package com.berkayb.soundconnect.shared.telemetry;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** Explicit local collector configuration. No OTEL_* environment fallback is used. */
@Getter
@Setter
@ConfigurationProperties("app.telemetry.metrics")
public class TelemetryMetricsProperties {
    private boolean enabled;
    private String endpoint = "";
    private String serviceName = "";
    private String environment = "";
    private Duration step = Duration.ofSeconds(30);
    private int maxSeries = 1024;

    public void validateEnabled() {
        if (!enabled) return;
        validateEndpoint(endpoint);
        if (!Set.of("soundconnect-api", "soundconnect-media-worker").contains(serviceName))
            throw new IllegalArgumentException("Telemetry metrics service-name must identify the API or media worker");
        if (!Set.of("local", "staging", "production").contains(environment))
            throw new IllegalArgumentException("Telemetry metrics environment must be local, staging or production");
        if (step == null || step.compareTo(Duration.ofSeconds(15)) < 0 || step.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Telemetry metrics step must be between 15 and 300 seconds");
        if (maxSeries < 256 || maxSeries > 2048)
            throw new IllegalArgumentException("Telemetry metrics max-series must be between 256 and 2048");
    }

    static void validateEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !"/v1/metrics".equals(uri.getRawPath()) || uri.getPort() < 1 || uri.getPort() > 65535
                    || !privateLiteral(uri.getHost())) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            // Do not include a rejected value: it may itself contain a credential.
            throw new IllegalArgumentException("Telemetry metrics endpoint must be an explicit private IPv4 HTTP(S) collector with port and /v1/metrics path");
        }
    }

    private static boolean privateLiteral(String host) {
        if (host == null || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = host.split("\\.");
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) return false;
        }
        return octets[0] == 127 || octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }
}
