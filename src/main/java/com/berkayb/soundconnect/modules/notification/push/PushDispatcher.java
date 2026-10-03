package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.push.transport.PushTransport;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.concurrent.Semaphore;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Component
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class PushDispatcher {
    private static final Logger log=LoggerFactory.getLogger(PushDispatcher.class);
    private final PushDeliveryStore store;
    private final PushTransport transport;
    private final PushProperties properties;
    private final TaskExecutor executor;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Semaphore slots;
    private final AtomicLong nextSendNanos=new AtomicLong();
    private final AtomicLong providerPauseUntil=new AtomicLong();

    public PushDispatcher(PushDeliveryStore store,PushTransport transport,PushProperties properties,
            @Qualifier("pushDeliveryExecutor") TaskExecutor executor,MeterRegistry metrics,@Qualifier("pushClock") Clock clock) {
        this.store=store; this.transport=transport; this.properties=properties; this.executor=executor;
        this.metrics=metrics; this.clock=clock; this.slots=new Semaphore(properties.getWorkerThreads());
    }

    @Scheduled(fixedDelayString="${app.notification.push.poll-delay-ms:1000}",initialDelayString="${app.notification.push.initial-delay-ms:10000}")
    public void poll() {
        if (clock.millis()<providerPauseUntil.get()) return;
        for(int i=0;i<properties.getWorkerThreads() && slots.tryAcquire();i++) {
            try { executor.execute(this::drain); }
            catch(RejectedExecutionException rejected) { slots.release(); }
        }
    }
    private void drain() {
        try {
            for(int i=0;i<properties.getBatchSize() && !Thread.currentThread().isInterrupted();i++) {
                if(clock.millis()<providerPauseUntil.get()) return;
                throttle();
                if(Thread.currentThread().isInterrupted() || clock.millis()<providerPauseUntil.get()) return;
                var candidate=store.claimNext();
                if(candidate.isEmpty()) return;
                var claim=candidate.get();
                try {
                    var envelope=store.prepare(claim);
                    if(envelope.isEmpty()) { metrics.counter("soundconnect.push.jobs","outcome","SUPPRESSED").increment(); continue; }
                    long started=System.nanoTime();
                    var result=transport.send(envelope.get());
                    metrics.timer("soundconnect.push.provider.duration").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);
                    store.complete(claim,result,PushTokenCipher.hash(envelope.get().token()));
                    metrics.counter("soundconnect.push.jobs","outcome",result.outcome().name()).increment();
                    if(result.retryAfter()!=null && !result.retryAfter().isNegative())
                        providerPauseUntil.accumulateAndGet(clock.millis()+result.retryAfter().toMillis(),Math::max);
                } catch(Exception failure) {
                    // Do not log exception messages: HTTP errors can contain device tokens.
                    log.warn("Push attempt failed; durable retry retained. deliveryId={}, exceptionType={}",claim.id(),failure.getClass().getSimpleName());
                    store.transientFailure(claim);
                }
            }
        } catch(Exception failure) {
            log.warn("Push worker paused; lease recovery remains enabled. exceptionType={}",failure.getClass().getSimpleName());
        } finally { slots.release(); }
    }
    private void throttle() {
        long interval=1_000_000_000L/properties.getMaxSendsPerSecond();
        long reserved=nextSendNanos.updateAndGet(previous->Math.max(previous,System.nanoTime())+interval);
        long wait=reserved-interval-System.nanoTime();
        if(wait>0) LockSupport.parkNanos(wait);
    }
}
