package com.berkayb.soundconnect.modules.notification.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.core.task.TaskRejectedException;
import java.util.concurrent.ThreadPoolExecutor;

/** Bounded best-effort projections cannot borrow a connection from a commit callback. */
@Component
@Slf4j
public class AfterCommitDeliveryExecutor {
    private final ThreadPoolTaskExecutor executor;
    public AfterCommitDeliveryExecutor() {
        executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("notification-delivery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(10);
        executor.initialize();
    }
    public void submit(Runnable work) {
        try { executor.execute(() -> {
            try { work.run(); }
            catch(RuntimeException failed) { log.warn("Committed notification projection skipped. exceptionType={}", failed.getClass().getSimpleName()); }
        }); }
        catch(TaskRejectedException full) { log.warn("Committed notification projection queue full or stopping; inbox remains authoritative"); }
    }
    @PreDestroy public void close() { executor.shutdown(); }
}
