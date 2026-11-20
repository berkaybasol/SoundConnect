package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImageVariantJobTelemetryTest {
    @Test void queuedDuplicateDoesNotCountAsAnotherAdmissionAndSaturationRetainsRetryBehavior() throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            var service = mock(ImageVariantBackfillService.class);
            var queued = new AtomicReference<Runnable>();
            var dispatcher = new ImageVariantJobDispatcher(service, queued::set);
            dispatcher.setTelemetry(new MediaWorkerMetrics(registry));
            var id = UUID.randomUUID();
            assertThat(dispatcher.submit(id)).isTrue();
            assertThat(dispatcher.submit(id)).isTrue();
            assertThat(registry.get("soundconnect.worker.admission").tags("workflow", "image_thumbnail", "outcome", "accepted").counter().count()).isEqualTo(1);
            queued.get().run();
            verify(service).backfillOne(id);
            var saturated = new ImageVariantJobDispatcher(service, task -> { throw new TaskRejectedException("full"); });
            saturated.setTelemetry(new MediaWorkerMetrics(registry));
            assertThat(saturated.submit(id)).isFalse();
            assertThat(registry.get("soundconnect.worker.admission").tags("workflow", "image_thumbnail", "outcome", "saturated").counter().count()).isEqualTo(1);
        } finally { registry.close(); }
    }
}
