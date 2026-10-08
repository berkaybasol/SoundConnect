package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

/** Reuses the configured storage client, with no bucket/key/exception details in the result. */
@Component
public class StorageHealthObservation {
    private final ObjectProvider<StorageClient> clients;
    private SystemHealthProbe.Reading last;
    private long lastAttemptNanos;

    public StorageHealthObservation(ObjectProvider<StorageClient> clients) { this.clients = clients; }

    public synchronized SystemHealthProbe.Reading read() {
        if (last != null && System.nanoTime() - lastAttemptNanos < Duration.ofSeconds(30).toNanos()) return last;
        lastAttemptNanos = System.nanoTime();
        StorageClient client = clients.getIfAvailable();
        if (client == null) return last = SystemHealthProbe.Reading.unknown(ReasonCode.NOT_CONFIGURED);
        StorageClient.ReadAccess access = client.probeReadAccess(Duration.ofMillis(1500));
        Status status = access == StorageClient.ReadAccess.AVAILABLE ? Status.UP
                : access == StorageClient.ReadAccess.UNAVAILABLE ? Status.DOWN : Status.UNKNOWN;
        return last = new SystemHealthProbe.Reading(status, SystemHealthSources.reason(status),
                status == Status.UNKNOWN ? null : Instant.now(), Map.of());
    }
}
