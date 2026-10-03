package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.config.MailQueueConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observes DLQ depth without taking delivery of failed mail jobs. The historical
 * bean name remains for existing application/test wiring; this is not a Rabbit
 * listener. Jobs stay ready in the broker for deliberate operator inspection.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mail.dlq-monitor.enabled", havingValue = "true", matchIfMissing = true)
public class DlqMailJobConsumer {

    private final AmqpAdmin admin;
    private final String queue;
    private final ExecutorService worker;
    private final AtomicBoolean observationScheduled = new AtomicBoolean();
    private volatile boolean stopped;
    private Observation previous;

    @Autowired
    public DlqMailJobConsumer(AmqpAdmin admin,
            @Value("${mail.dlq:" + MailQueueConfig.MAIL_DLQ_DEFAULT + "}") String queue) {
        this(admin, queue, new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task, "mail-dlq-monitor");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    DlqMailJobConsumer(AmqpAdmin admin, String queue, ExecutorService worker) {
        this.admin = Objects.requireNonNull(admin);
        if (queue == null || queue.isBlank()) {
            throw new IllegalArgumentException("Mail DLQ name is required");
        }
        this.queue = queue;
        this.worker = Objects.requireNonNull(worker);
    }

    @Scheduled(fixedDelayString = "${mail.dlq-monitor.interval-ms:60000}",
            initialDelayString = "${mail.dlq-monitor.initial-delay-ms:60000}")
    public void scheduleObservation() {
        if (stopped || !observationScheduled.compareAndSet(false, true)) return;
        try {
            // A slow/offline broker must never occupy the application's shared
            // scheduler. The gate bounds active + queued observations to one.
            worker.execute(() -> {
                try {
                    if (!stopped) observeDepth();
                } finally {
                    observationScheduled.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            observationScheduled.set(false);
        }
    }

    @PreDestroy
    public void close() {
        stopped = true;
        worker.shutdownNow();
        observationScheduled.set(false);
    }

    public synchronized void observeDepth() {
        if (stopped) return;
        Observation current;
        try {
            // RabbitAdmin implements this with queueDeclarePassive: no basicGet,
            // consumer registration, ACK, conversion, or message-body access.
            QueueInformation info = admin.getQueueInfo(queue);
            current = info == null ? Observation.unavailable()
                    : new Observation(info.getMessageCount(), info.getConsumerCount());
        } catch (RuntimeException ignored) {
            // Broker errors can contain connection details; emit no exception or
            // payload. A failed query is unknown, never an empty/healthy queue.
            current = Observation.unavailable();
        }
        if (current.equals(previous)) return;
        Observation before = previous;
        previous = current;
        if (current.readyMessages() == null) {
            log.warn("Mail DLQ depth unavailable; no jobs were consumed or acknowledged.");
        } else if (current.readyMessages() > 0 || current.consumers() > 0) {
            log.warn("Mail DLQ state: readyMessages={}, consumers={}; retained for operator review.",
                    current.readyMessages(), current.consumers());
        } else if (before != null) {
            log.info("Mail DLQ state: readyMessages=0, consumers=0.");
        }
    }

    private record Observation(Integer readyMessages, Integer consumers) {
        static Observation unavailable() {
            return new Observation(null, null);
        }
    }
}
