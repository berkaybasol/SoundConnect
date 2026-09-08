package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class AnalyticsIdentity {
    public static final UUID NONE = new UUID(0, 0);
    private final AnalyticsProperties properties;
    public AnalyticsIdentity(AnalyticsProperties properties) {
        this.properties = properties;
        if (properties.isEnabled() && !validSecret()) {
            throw new IllegalStateException("Enabled venue analytics requires a dedicated HMAC secret of at least 32 UTF-8 bytes");
        }
    }
    public void requireEnabled() {
        if (!properties.isEnabled() || !validSecret()) throw unavailable();
    }
    public void requireReportingEnabled() {
        requireEnabled();
        if (!properties.isReportingEnabled()) throw unavailable();
    }
    public String actor(UUID userId, UUID clientId) { return userId == null ? "installation:" + clientId : "account:" + userId; }
    public byte[] viewer(UUID venueId, String actor) { return digest("viewer|" + venueId + "|" + actor); }
    public byte[] receiptActor(String actor) { return digest("receipt-actor|" + actor); }
    public byte[] payload(UUID clientId, AnalyticsRequest.Observation observation) {
        return digest("payload|" + clientId + "|" + observation.id() + "|" + observation.type() + "|"
                + observation.eventId() + "|" + observation.venueId() + "|" + observation.sourceEventId() + "|" + observation.observedAt());
    }
    public String quotaKey(String scope, String subject) { return HexFormat.of().formatHex(digest("quota|" + scope + "|" + subject)); }
    private byte[] digest(String value) {
        requireEnabled();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes(), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException("HMAC-SHA256 unavailable", impossible); }
    }
    private byte[] secretBytes() { return properties.getHmacSecret() == null ? new byte[0] : properties.getHmacSecret().getBytes(StandardCharsets.UTF_8); }
    private boolean validSecret() { return properties.getHmacSecret() != null && !properties.getHmacSecret().isBlank() && secretBytes().length >= 32; }
    static ServiceUnavailableRetryException unavailable() { return new ServiceUnavailableRetryException(ErrorType.ANALYTICS_UNAVAILABLE, 30); }
}
