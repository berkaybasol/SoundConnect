package com.berkayb.soundconnect.shared.mail.consumer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@ExtendWith(MockitoExtension.class)
class DlqMailJobConsumerTest {
    private static final String QUEUE = "fixture.mail.dlq";
    @Mock private AmqpAdmin admin;
    private DlqMailJobConsumer monitor;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        monitor = new DlqMailJobConsumer(admin, QUEUE);
        logger = (Logger) LoggerFactory.getLogger(DlqMailJobConsumer.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        monitor.close();
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void repeatedObservationOfMoreThanPrefetchJobsOnlyQueriesMetadata() {
        when(admin.getQueueInfo(QUEUE)).thenReturn(new QueueInformation(QUEUE, 37, 0));

        monitor.observeDepth();
        monitor.observeDepth();
        monitor.observeDepth();

        verify(admin, times(3)).getQueueInfo(QUEUE);
        verifyNoMoreInteractions(admin);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
                .contains("readyMessages=37", "consumers=0", "retained");
    }

    @Test
    void unavailableQueueIsNotReportedAsEmptyAndCanRecover() {
        when(admin.getQueueInfo(QUEUE)).thenReturn(null, null,
                new QueueInformation(QUEUE, 37, 0), new QueueInformation(QUEUE, 0, 0));

        for (int i = 0; i < 4; i++) monitor.observeDepth();

        verify(admin, times(4)).getQueueInfo(QUEUE);
        verifyNoMoreInteractions(admin);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "Mail DLQ depth unavailable; no jobs were consumed or acknowledged.",
                        "Mail DLQ state: readyMessages=37, consumers=0; retained for operator review.",
                        "Mail DLQ state: readyMessages=0, consumers=0.");
    }

    @Test
    void connectionFailureIsContainedRedactedAndRetriedOnNextObservation() {
        when(admin.getQueueInfo(QUEUE))
                .thenReturn(new QueueInformation(QUEUE, 37, 0))
                .thenThrow(new AmqpConnectException(new ConnectException(
                        "amqp://private-user:secret@private-host/ alice@example.test otp=123456")))
                .thenReturn(new QueueInformation(QUEUE, 37, 0));

        assertThatCode(() -> {
            monitor.observeDepth();
            monitor.observeDepth();
            monitor.observeDepth();
        }).doesNotThrowAnyException();

        verify(admin, times(3)).getQueueInfo(QUEUE);
        verifyNoMoreInteractions(admin);
        assertThat(logs.list).hasSize(3);
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .doesNotContain("private-user", "secret", "private-host", "alice@", "123456");
        });
        assertThat(logs.list.getLast().getFormattedMessage()).contains("readyMessages=37");
    }

    @Test
    void existingConsumersAreReportedEvenWhenNoMessagesAreReady() {
        when(admin.getQueueInfo(QUEUE)).thenReturn(new QueueInformation(QUEUE, 0, 1));

        monitor.observeDepth();

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("readyMessages=0", "consumers=1");
        verify(admin).getQueueInfo(QUEUE);
        verifyNoMoreInteractions(admin);
    }

    @Test
    void initiallyEmptyQueueNeedsNoAlarm() {
        when(admin.getQueueInfo(QUEUE)).thenReturn(new QueueInformation(QUEUE, 0, 0));

        monitor.observeDepth();
        monitor.observeDepth();

        assertThat(logs.list).isEmpty();
        verify(admin, times(2)).getQueueInfo(QUEUE);
        verifyNoMoreInteractions(admin);
    }

    @Test
    void slowBrokerDoesNotBlockSchedulerOrAccumulateOverlappingPolls() throws Exception {
        monitor.close();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        monitor = new DlqMailJobConsumer(admin, QUEUE, executor);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(admin.getQueueInfo(QUEUE)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new QueueInformation(QUEUE, 37, 0);
        });

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), monitor::scheduleObservation);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 100; i++) monitor.scheduleObservation();
            });
            verify(admin).getQueueInfo(QUEUE);
        } finally {
            release.countDown();
        }
        // The marker runs after every already-enqueued poll. Extra scheduled
        // calls must not have accumulated behind the blocked first request.
        executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        verify(admin).getQueueInfo(QUEUE);
        monitor.scheduleObservation();
        executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        verify(admin, times(2)).getQueueInfo(QUEUE);
        verifyNoMoreInteractions(admin);
    }

    @Test
    void shutdownInterruptsWorkerAndPreventsAnyNewPoll() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(admin.getQueueInfo(QUEUE)).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new QueueInformation(QUEUE, 37, 0);
        });

        monitor.scheduleObservation();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        monitor.close();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        monitor.scheduleObservation();
        monitor.observeDepth();

        verify(admin).getQueueInfo(QUEUE);
        verifyNoMoreInteractions(admin);
    }

    @Test
    void rejectedTaskReleasesGateForTheNextScheduledTick() {
        ExecutorService executor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("fixture rejection"))
                .doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return null;
                }).when(executor).execute(any(Runnable.class));
        when(admin.getQueueInfo(QUEUE)).thenReturn(new QueueInformation(QUEUE, 37, 0));
        DlqMailJobConsumer isolated = new DlqMailJobConsumer(admin, QUEUE, executor);
        try {
            assertThatCode(() -> {
                isolated.scheduleObservation();
                isolated.scheduleObservation();
            }).doesNotThrowAnyException();

            verify(executor, times(2)).execute(any(Runnable.class));
            verify(admin).getQueueInfo(QUEUE);
            verifyNoMoreInteractions(admin);
        } finally {
            isolated.close();
        }
        verify(executor).shutdownNow();
        verifyNoMoreInteractions(executor);
    }
}
