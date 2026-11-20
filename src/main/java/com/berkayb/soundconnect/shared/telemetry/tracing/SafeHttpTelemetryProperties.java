package com.berkayb.soundconnect.shared.telemetry.tracing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Set;

/** Explicit opt-in. Credentials belong to the local collector, never the application. */
@ConfigurationProperties("app.observability.http-telemetry")
public class SafeHttpTelemetryProperties {
    private boolean enabled;
    private double sampleProbability = 0.1;
    private String collectorEndpoint = "http://127.0.0.1:4318";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getSampleProbability() { return sampleProbability; }
    public void setSampleProbability(double sampleProbability) { this.sampleProbability = sampleProbability; }
    public String getCollectorEndpoint() { return collectorEndpoint; }
    public void setCollectorEndpoint(String collectorEndpoint) { this.collectorEndpoint = collectorEndpoint; }

    String validatedEndpoint() {
        if (!Double.isFinite(sampleProbability) || sampleProbability < 0 || sampleProbability > 1) {
            throw new IllegalArgumentException("HTTP telemetry sample probability must be between zero and one");
        }
        URI uri;
        try { uri = URI.create(collectorEndpoint); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid local telemetry collector endpoint"); }
        if (!"http".equals(uri.getScheme())
                || uri.getHost() == null
                || !Set.of("127.0.0.1", "localhost", "172.30.241.1").contains(uri.getHost())
                || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("HTTP telemetry requires an explicit local collector host and port without credentials or path");
        }
        return "http://" + uri.getHost() + ":" + uri.getPort();
    }
}
