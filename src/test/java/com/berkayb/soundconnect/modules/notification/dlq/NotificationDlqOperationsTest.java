package com.berkayb.soundconnect.modules.notification.dlq;

import com.rabbitmq.client.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NotificationDlqOperationsTest {
    NotificationDlqProperties config;
    NotificationDlqBroker broker;
    NotificationDlqOperations ops;
    Channel channel;
    NotificationDlqOperations.Selection selection;
    @BeforeEach void setup() throws Exception {
        config = new NotificationDlqProperties(); config.setReplayEnabled(true); config.setWindow(2);
        broker = mock(NotificationDlqBroker.class); when(broker.virtualHost()).thenReturn("/");
        channel = mock(Channel.class);
        when(channel.basicGet("dlq", false)).thenReturn(NotificationDlqMessageTest.delivery(NotificationDlqMessageTest.BODY, Map.of()), null);
        when(channel.waitForConfirms(anyLong())).thenReturn(true);
        doAnswer(i -> {
            var s = new NotificationDlqBroker.Session(); s.channel = channel;
            var future = new CompletableFuture<>();
            try { future.complete(((NotificationDlqBroker.Work<?>)i.getArgument(1)).run(s)); }
            catch (Exception e) { future.completeExceptionally(e); }
            return future;
        }).when(broker).submit(anyBoolean(), any());
        ops = new NotificationDlqOperations(broker, config, new SimpleMeterRegistry(), "dlq", "ingress", "exchange", "notification.event");
        var m = NotificationDlqMessage.parse(NotificationDlqMessageTest.delivery(NotificationDlqMessageTest.BODY, Map.of()), "/\ndlq", "ingress", 65536, NotificationDlqMessageTest.NOW).metadata();
        selection = new NotificationDlqOperations.Selection(m.eventId(), m.fingerprint());
    }
    @Test void confirmsAndRouteMustPrecedeSingleSourceAck() throws Exception {
        assertThat(ops.replay(UUID.randomUUID(), selection).outcome()).isEqualTo("REPLAYED");
        var order = inOrder(channel);
        order.verify(channel).basicGet("dlq", false);
        order.verify(channel).confirmSelect();
        order.verify(channel).basicPublish(eq("exchange"), eq("notification.event"), eq(true), any(), eq(NotificationDlqMessageTest.BODY.getBytes()));
        order.verify(channel).waitForConfirms(anyLong());
        order.verify(channel).basicAck(7, false);
        verify(channel, never()).basicAck(anyLong(), eq(true));
    }
    @Test void controlledNackRetainsSource() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenReturn(false);
        assertThat(ops.replay(UUID.randomUUID(), selection).outcome()).isEqualTo("PUBLISH_NACK");
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
    @Test void controlledReturnEvenWithConfirmRetainsSource() throws Exception {
        doAnswer(i -> { ((ReturnCallback)i.getArgument(0)).handle(new Return(312, "NO_ROUTE", "exchange", "key", null, new byte[0])); return null; }).when(channel).addReturnListener(any(ReturnCallback.class));
        assertThat(ops.replay(UUID.randomUUID(), selection).outcome()).isEqualTo("UNROUTABLE");
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
    @Test void controlledTimeoutOrInterruptionNeverAcks() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenThrow(new TimeoutException());
        assertThat(ops.replay(UUID.randomUUID(), selection).brokerAcceptance()).isEqualTo("UNKNOWN");
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
    @Test void failureAfterConfirmReportsAckAmbiguity() throws Exception {
        doThrow(new IOException("private credential must not escape")).when(channel).basicAck(7, false);
        var result = ops.replay(UUID.randomUUID(), selection);
        assertThat(result.outcome()).isEqualTo("SOURCE_ACK_AMBIGUOUS");
        assertThat(result.brokerAcceptance()).isEqualTo("CONFIRMED_ROUTED");
        assertThat(result.toString()).doesNotContain("credential", "private");
    }
    @Test void disabledReplayDoesNotTouchBroker() {
        config.setReplayEnabled(false);
        assertThat(ops.replay(UUID.randomUUID(), selection).outcome()).isEqualTo("DISABLED");
        verify(broker, never()).submit(anyBoolean(), any());
    }
    @Test void staleSelectionCannotAcknowledgeSibling() throws Exception {
        assertThat(ops.replay(UUID.randomUUID(), new NotificationDlqOperations.Selection(selection.eventId(), "0".repeat(64))).outcome()).isEqualTo("NOT_FOUND_IN_WINDOW");
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
    }
    @Test void boundedInspectionDoesNotAckOrPublish() throws Exception {
        when(channel.basicGet("dlq", false)).thenReturn(NotificationDlqMessageTest.delivery(NotificationDlqMessageTest.BODY, Map.of()));
        assertThat(ops.inspect(UUID.randomUUID()).examined()).isEqualTo(2);
        verify(channel, times(2)).basicGet("dlq", false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
    }
    @Test void windowByteLimitIsFinite() throws Exception {
        config.setMaxWindowBytes(1);
        assertThat(ops.inspect(UUID.randomUUID()).outcome()).isEqualTo("WINDOW_BYTE_LIMIT");
        verify(channel, times(1)).basicGet("dlq", false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
    @Test void initialSummaryAndRejectionAreUnknownNotHealthy() {
        assertThat(ops.summary().status()).isEqualTo("UNKNOWN");
        assertThat(ops.summary().readyMessages()).isNull();
        doThrow(new RejectedExecutionException()).when(broker).submit(anyBoolean(), any());
        ops.observe();
        assertThat(ops.summary().status()).isEqualTo("UNAVAILABLE");
        assertThat(ops.inspect(UUID.randomUUID()).outcome()).isEqualTo("BUSY_OR_STOPPED");
    }
}
