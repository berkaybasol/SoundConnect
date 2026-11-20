package com.berkayb.soundconnect.shared.telemetry.tracing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provider telemetry shares the private HTTP SDK and collector settings. */
@ConfigurationProperties("app.observability.dependency-telemetry")
public class SafeDependencyTelemetryProperties {
    private boolean enabled;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
