package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPageResponse;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Fixed provider/type/outcome tags only: never attach account, session or target identifiers. */
@Component
public class MusicianFeedMetrics {
    private static final String PREFIX = "soundconnect.musician.feed.";
    private final MeterRegistry registry;

    public MusicianFeedMetrics(MeterRegistry registry) { this.registry = registry; }

    static MusicianFeedMetrics unbound() { return new MusicianFeedMetrics(new CompositeMeterRegistry()); }

    void bindExecutor(ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor pool) {
            registry.gauge(PREFIX + "providers.active", pool, ThreadPoolExecutor::getActiveCount);
            registry.gauge(PREFIX + "providers.queued", pool, value -> value.getQueue().size());
        }
    }

    MusicianFeedPageResponse page(Supplier<MusicianFeedPageResponse> work) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            MusicianFeedPageResponse page = work.get();
            page.items().forEach(value -> registry.counter(PREFIX + "response.items", "type", value.type().name()).increment());
            return page;
        }
        catch (RuntimeException failure) { outcome = "error"; throw failure; }
        finally { registry.timer(PREFIX + "page.duration", "outcome", outcome)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS); }
    }

    <T> T execute(String provider, Callable<T> work) throws Exception {
        long start = System.nanoTime();
        try { return work.call(); }
        finally { registry.timer(PREFIX + "provider.execution", "provider", provider)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS); }
    }

    void providerResult(String provider, String outcome) {
        registry.counter(PREFIX + "provider.results", "provider", provider, "outcome", outcome).increment();
    }

    void candidates(Collection<MusicianFeedCandidate> values) {
        values.forEach(value -> registry.counter(PREFIX + "candidates", "type", value.type().name(),
                "lane", value.lane().name()).increment());
    }

    void historyUnavailable() { registry.counter(PREFIX + "history.unavailable").increment(); }
}
