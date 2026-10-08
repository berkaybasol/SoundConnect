package com.berkayb.soundconnect.modules.admin.health;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthProbe.Reading;
import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

/** Uses the same passive queue-info API as the existing mail DLQ observer. */
@Component
public class RabbitQueueHealthSources {
    private final ObjectProvider<AmqpAdmin> admin;
    private final Environment environment;
    private final SystemHealthProperties properties;

    public RabbitQueueHealthSources(ObjectProvider<AmqpAdmin> admin, Environment environment, SystemHealthProperties properties) {
        this.admin = admin; this.environment = environment; this.properties = properties;
    }

    List<SystemHealthProbe> probes() {
        return List.of(
                probe("notificationQueue", "Bildirim kuyruğu", "app.messaging.notification.queue", "notification.queue", false),
                probe("mailQueue", "E-posta kuyruğu", "mail.queueName", "mail.queue", false),
                probe("mailDlq", "E-posta hata kuyruğu", "mail.dlq", "mail.queue.dlq", true),
                probe("mediaQueue", "Medya kuyruğu", "rabbitmq.media.queues.videoHls", "media.transcode.video.hls", false),
                probe("mediaDlq", "Medya hata kuyruğu", "rabbitmq.media.queues.videoHlsDlq", "media.transcode.video.hls.dlq", true));
    }

    private SystemHealthProbe probe(String id, String label, String property, String fallback, boolean deadLetter) {
        return new SystemHealthProbe(id, label,
                "Hazır mesajlar ve tüketici sayısı; toplam, işlenmekte olan mesajlar ve en eski mesaj yaşı ölçülmüyor. Teslim ayrıca doğrulanır.", () -> {
            AmqpAdmin client = admin.getIfAvailable();
            if (client == null) return Reading.unknown(ReasonCode.NOT_CONFIGURED);
            String queue = environment.getProperty(property, fallback);
            if (queue.isBlank() || queue.length() > 255) return Reading.unknown(ReasonCode.NOT_CONFIGURED);
            var info = client.getQueueInfo(queue);
            if (info == null) return Reading.unknown(ReasonCode.PROBE_FAILED);
            int ready = info.getMessageCount(), consumers = info.getConsumerCount();
            if (ready < 0 || consumers < 0) return Reading.unknown(ReasonCode.INVALID_MEASUREMENT);
            Status status = deadLetter ? (ready > 0 || consumers > 0 ? Status.DEGRADED : Status.UP)
                    : consumers == 0 ? (ready > 0 ? Status.DOWN : Status.DEGRADED)
                    : ready >= properties.getQueueReadyThreshold() ? Status.DEGRADED : Status.UP;
            return new Reading(status, SystemHealthSources.reason(status), Instant.now(),
                    Map.of("readyMessages", (double) ready, "consumers", (double) consumers));
        });
    }
}
