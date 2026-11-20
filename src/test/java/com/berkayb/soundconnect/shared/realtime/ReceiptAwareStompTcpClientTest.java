package com.berkayb.soundconnect.shared.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.tcp.ReconnectStrategy;
import org.springframework.messaging.tcp.TcpConnection;
import org.springframework.messaging.tcp.TcpConnectionHandler;
import org.springframework.messaging.tcp.TcpOperations;

class ReceiptAwareStompTcpClientTest {
    TcpOperations<byte[]> delegate = mock(TcpOperations.class);
    TcpConnectionHandler<byte[]> handler = mock(TcpConnectionHandler.class);
    TcpConnection<byte[]> wire = mock(TcpConnection.class);
    ReceiptAwareStompTcpClient client;
    TcpConnectionHandler<byte[]> inbound;
    TcpConnection<byte[]> outbound;

    @BeforeEach
    void start() {
        when(delegate.connectAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(delegate.shutdownAsync()).thenReturn(CompletableFuture.completedFuture(null));
        when(wire.sendAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        client = new ReceiptAwareStompTcpClient(delegate, Duration.ofMillis(150));
        client.connectAsync(handler).join();
        ArgumentCaptor<TcpConnectionHandler<byte[]>> listener = ArgumentCaptor.forClass(TcpConnectionHandler.class);
        verify(delegate).connectAsync(listener.capture());
        inbound = listener.getValue();
        inbound.afterConnected(wire);
        ArgumentCaptor<TcpConnection<byte[]>> transport = ArgumentCaptor.forClass(TcpConnection.class);
        verify(handler).afterConnected(transport.capture());
        outbound = transport.getValue();
    }

    @AfterEach
    void stop() {
        client.shutdownAsync().join();
    }

    @Test
    void exactReceiptDefersEofUntilRelayClosesAndDoesNotSendDuplicateDisconnect() {
        disconnectAndReceipt("last", "last");
        inbound.afterConnectionClosed();
        verify(handler, never()).afterConnectionClosed();
        outbound.sendAsync(frame(StompCommand.DISCONNECT, null)).join();
        verify(wire, times(1)).sendAsync(any());
        outbound.close();
        verify(wire).close();
        verify(handler).afterConnectionClosed();
        verify(handler, after(230).never()).handleFailure(any());
    }

    @Test
    void successfulReceiptAlsoAvoidsDuplicateDisconnectBeforeBrokerEof() {
        disconnectAndReceipt("last", "last");
        outbound.sendAsync(frame(StompCommand.DISCONNECT, null)).join();
        verify(wire, times(1)).sendAsync(any());
        outbound.close();
        inbound.afterConnectionClosed();
        verify(handler).afterConnectionClosed();
    }

    @Test
    void missingOrWrongReceiptKeepsEofAnImmediateFailure() {
        disconnectAndReceipt("last", "other");
        inbound.afterConnectionClosed();
        verify(handler).afterConnectionClosed();
    }

    @Test
    void receiptBeforeDisconnectCannotAuthorizeGracefulEof() {
        inbound.handleMessage(frame(StompCommand.RECEIPT, "last"));
        outbound.sendAsync(frame(StompCommand.DISCONNECT, "last"));
        inbound.afterConnectionClosed();
        verify(handler).afterConnectionClosed();
    }

    @Test
    void receiptlessDisconnectKeepsExistingBehavior() {
        outbound.sendAsync(frame(StompCommand.DISCONNECT, null));
        inbound.afterConnectionClosed();
        verify(wire).sendAsync(any());
        verify(handler).afterConnectionClosed();
    }

    @Test
    void actualBrokerErrorIsForwardedAndCancelsGrace() {
        disconnectAndReceipt("last", "last");
        Message<byte[]> error = frame(StompCommand.ERROR, null);
        inbound.handleMessage(error);
        inbound.afterConnectionClosed();
        verify(handler).handleMessage(error);
        verify(handler).afterConnectionClosed();
        verify(handler, after(230).never()).handleFailure(any());
    }

    @Test
    void transportFailureRemainsVisibleAfterMatchingReceipt() {
        disconnectAndReceipt("last", "last");
        IllegalStateException failure = new IllegalStateException("synthetic");
        inbound.handleFailure(failure);
        inbound.afterConnectionClosed();
        verify(handler).handleFailure(failure);
        verify(handler).afterConnectionClosed();
    }

    @Test
    void failedWriteRemainsVisibleEvenWhenReceiptRacesWriteCompletion() {
        CompletableFuture<Void> write = new CompletableFuture<>();
        when(wire.sendAsync(any())).thenReturn(write);
        CompletableFuture<Void> forwarded = outbound.sendAsync(frame(StompCommand.DISCONNECT, "last"));
        inbound.handleMessage(frame(StompCommand.RECEIPT, "last"));
        write.completeExceptionally(new IllegalStateException("synthetic"));
        assertThat(forwarded).isCompletedExceptionally();
        inbound.afterConnectionClosed();
        verify(handler).afterConnectionClosed();
    }

    @Test
    void missingClientCloseExpiresEvenWhenBrokerHasAlreadyClosed() {
        disconnectAndReceipt("last", "last");
        inbound.afterConnectionClosed();
        verify(handler, timeout(1500)).handleFailure(any(TimeoutException.class));
        verify(handler, never()).afterConnectionClosed();
    }

    @Test
    void missingClientCloseAlsoExpiresWhenBrokerRemainsOpen() {
        disconnectAndReceipt("last", "last");
        verify(handler, timeout(1500)).handleFailure(any(TimeoutException.class));
    }

    @Test
    void shutdownCancelsPendingGraceAndOwnsDelegateShutdown() {
        disconnectAndReceipt("last", "last");
        client.shutdownAsync().join();
        verify(delegate).shutdownAsync();
        verify(handler, after(230).never()).handleFailure(any());
    }

    @Test
    void reconnectingSystemConnectionIsPassedThroughUnchanged() {
        ReconnectStrategy strategy = mock(ReconnectStrategy.class);
        CompletableFuture<Void> connected = CompletableFuture.completedFuture(null);
        when(delegate.connectAsync(handler, strategy)).thenReturn(connected);
        assertThat(client.connectAsync(handler, strategy)).isSameAs(connected);
        verify(delegate).connectAsync(handler, strategy);
    }

    private void disconnectAndReceipt(String request, String response) {
        outbound.sendAsync(frame(StompCommand.DISCONNECT, request)).join();
        inbound.handleMessage(frame(StompCommand.RECEIPT, response));
    }

    private Message<byte[]> frame(StompCommand command, String receipt) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        if (command == StompCommand.RECEIPT) headers.setReceiptId(receipt);
        else if (receipt != null) headers.setReceipt(receipt);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
