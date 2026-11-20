package com.berkayb.soundconnect.shared.realtime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.tcp.ReconnectStrategy;
import org.springframework.messaging.tcp.TcpConnection;
import org.springframework.messaging.tcp.TcpConnectionHandler;
import org.springframework.messaging.tcp.TcpOperations;

/**
 * Gives a client a bounded window to close its WebSocket after a successful
 * STOMP DISCONNECT receipt. Rabbit closes TCP immediately after the receipt;
 * Spring 6.2's relay otherwise turns that expected EOF into ERROR/close 1002.
 *
 * Only an EOF following the exact requested receipt is delayed. Broker ERROR,
 * transport failures, missing/wrong receipts and the system/reconnecting
 * connection retain Spring's failure handling. No frame is manufactured and
 * no error text is inspected. The relay still owns connection/session cleanup.
 */
public final class ReceiptAwareStompTcpClient implements TcpOperations<byte[]> {
    private final TcpOperations<byte[]> delegate;
    private final long graceMillis;
    private final ScheduledThreadPoolExecutor scheduler;

    public ReceiptAwareStompTcpClient(TcpOperations<byte[]> delegate) {
        this(delegate, Duration.ofSeconds(5));
    }

    ReceiptAwareStompTcpClient(TcpOperations<byte[]> delegate, Duration grace) {
        if (delegate == null || grace.isNegative() || grace.isZero() || grace.toMillis() < 1
                || grace.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("A TCP client and bounded disconnect grace are required");
        }
        this.delegate = delegate;
        this.graceMillis = grace.toMillis();
        this.scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "stomp-disconnect-grace");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public CompletableFuture<Void> connectAsync(TcpConnectionHandler<byte[]> handler) {
        return delegate.connectAsync(new ClientConnection(handler));
    }

    @Override
    public CompletableFuture<Void> connectAsync(TcpConnectionHandler<byte[]> handler, ReconnectStrategy strategy) {
        // The system connection is long-lived and has its own reconnect lifecycle.
        return delegate.connectAsync(handler, strategy);
    }

    @Override
    public CompletableFuture<Void> shutdownAsync() {
        scheduler.shutdownNow();
        return delegate.shutdownAsync();
    }

    private final class ClientConnection implements TcpConnectionHandler<byte[]>, TcpConnection<byte[]> {
        private final TcpConnectionHandler<byte[]> handler;
        private TcpConnection<byte[]> connection;
        private String disconnectReceipt;
        private boolean receiptCompleted;
        private boolean brokerClosed;
        private boolean closeRequested;
        private boolean failed;
        private ScheduledFuture<?> deadline;

        ClientConnection(TcpConnectionHandler<byte[]> handler) {
            this.handler = handler;
        }

        @Override
        public void afterConnected(TcpConnection<byte[]> connection) {
            synchronized (this) {
                this.connection = connection;
            }
            handler.afterConnected(this);
        }

        @Override
        public void afterConnectFailure(Throwable failure) {
            fail();
            handler.afterConnectFailure(failure);
        }

        @Override
        public void handleMessage(Message<byte[]> message) {
            StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            synchronized (this) {
                if (headers != null && headers.getCommand() == StompCommand.ERROR) {
                    fail();
                } else if (headers != null && headers.getCommand() == StompCommand.RECEIPT
                        && !failed && !closeRequested && !receiptCompleted && disconnectReceipt != null
                        && disconnectReceipt.equals(headers.getReceiptId())) {
                    receiptCompleted = true;
                    deadline = scheduler.schedule(this::graceExpired, graceMillis, TimeUnit.MILLISECONDS);
                }
            }
            handler.handleMessage(message);
        }

        private void graceExpired() {
            synchronized (this) {
                if (closeRequested || failed) return;
                failed = true;
            }
            // The client failed to finish its close handshake. Keep this a failure,
            // and let the relay clear its registry and the underlying connection.
            handler.handleFailure(new TimeoutException("STOMP disconnect close deadline exceeded"));
        }

        @Override
        public void handleFailure(Throwable failure) {
            fail();
            handler.handleFailure(failure);
        }

        private synchronized void fail() {
            failed = true;
            if (deadline != null) deadline.cancel(false);
        }

        @Override
        public void afterConnectionClosed() {
            synchronized (this) {
                brokerClosed = true;
                if (receiptCompleted && !failed && !closeRequested) return;
            }
            handler.afterConnectionClosed();
        }

        @Override
        public CompletableFuture<Void> sendAsync(Message<byte[]> message) {
            StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            synchronized (this) {
                if (headers != null && headers.getCommand() == StompCommand.DISCONNECT) {
                    if (receiptCompleted && !failed && headers.getReceipt() == null) {
                        // Spring emits this cleanup command when the WebSocket closes.
                        // Rabbit has already accepted DISCONNECT and may close TCP, so
                        // acknowledge only this local cleanup, allowing clearConnection().
                        return CompletableFuture.completedFuture(null);
                    }
                    // Record before send: the broker receipt may arrive before the
                    // asynchronous write future completes. Never replace a pending ID.
                    if (disconnectReceipt == null && headers.getReceipt() != null
                            && !headers.getReceipt().isBlank()) {
                        disconnectReceipt = headers.getReceipt();
                    }
                }
            }
            return connection.sendAsync(message).whenComplete((unused, failure) -> {
                if (failure != null) fail();
            });
        }

        @Override
        public void onReadInactivity(Runnable task, long duration) {
            connection.onReadInactivity(task, duration);
        }

        @Override
        public void onWriteInactivity(Runnable task, long duration) {
            connection.onWriteInactivity(task, duration);
        }

        @Override
        public void close() {
            boolean notifyClosed;
            synchronized (this) {
                if (closeRequested) return;
                closeRequested = true;
                if (deadline != null) deadline.cancel(false);
                notifyClosed = brokerClosed && receiptCompleted && !failed;
            }
            connection.close();
            // clearConnection() nulls Spring's TCP reference before calling close,
            // so delivering the delayed EOF here performs normal cleanup only.
            if (notifyClosed) handler.afterConnectionClosed();
        }
    }
}
