package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.push.transport.PushTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PushDispatcherTest {
    @Test void workerWaitingForRateSlotHonorsAnotherWorkersProviderPauseBeforeClaim() throws Exception {
        checkWaitingWorker(false);
    }
    @Test void interruptedRateWaitDoesNotClaimAnotherJob() throws Exception {
        checkWaitingWorker(true);
    }
    private void checkWaitingWorker(boolean interrupt) throws Exception {
        var store=mock(PushDeliveryStore.class); var provider=mock(PushTransport.class);
        var properties=new PushProperties(); properties.setWorkerThreads(1); properties.setBatchSize(1);
        var worker=new AtomicReference<Thread>();
        TaskExecutor executor=command->{var thread=new Thread(command,"fixture-push-dispatcher"); worker.set(thread); thread.start();};
        var metrics=new SimpleMeterRegistry(); var clock=Clock.systemUTC();
        var dispatcher=new PushDispatcher(store,provider,properties,executor,metrics,clock);
        ((AtomicLong)ReflectionTestUtils.getField(dispatcher,"nextSendNanos"))
                .set(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800));
        dispatcher.poll(); var thread=worker.get();
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(thread.getState()!=Thread.State.TIMED_WAITING && thread.isAlive() && System.nanoTime()<until) Thread.sleep(1);
            assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            if(interrupt) thread.interrupt();
            else ((AtomicLong)ReflectionTestUtils.getField(dispatcher,"providerPauseUntil")).set(clock.millis()+10_000);
            thread.join(2_000); assertThat(thread.isAlive()).isFalse();
            verifyNoInteractions(store,provider);
        } finally { thread.interrupt(); thread.join(2_000); metrics.close(); }
    }
}
