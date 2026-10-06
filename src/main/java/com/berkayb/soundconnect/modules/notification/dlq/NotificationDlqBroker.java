package com.berkayb.soundconnect.modules.notification.dlq;

import com.rabbitmq.client.*;
import jakarta.annotation.PreDestroy;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.net.Socket;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Dedicated short-lived connections: never close/reset a shared product connection.
 * One operation and one passive observation at a time, zero pending work per lane.
 * Deadlines abort connections (requeueing manual deliveries), even on caller cancellation.
 */
@Component
public class NotificationDlqBroker implements AutoCloseable {
    private final ConnectionFactory factory;
    private final NotificationDlqProperties properties;
    private final ExecutorService observer = lane("notification-dlq-observer");
    private final ExecutorService operator = lane("notification-dlq-operator");
    private final ScheduledThreadPoolExecutor timer = deadlineTimer();
    private final Set<Session> active = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    public NotificationDlqBroker(org.springframework.amqp.rabbit.connection.ConnectionFactory source,
                                 NotificationDlqProperties properties) {
        if (!(source instanceof AbstractConnectionFactory nativeSource))
            throw new IllegalStateException("DLQ ops requires the configured Rabbit connection factory");
        this.properties = properties;
        factory = nativeSource.getRabbitConnectionFactory().clone();
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        factory.useBlockingIo();
        factory.setConnectionTimeout(properties.getRpcTimeoutMs());
        factory.setHandshakeTimeout(properties.getRpcTimeoutMs());
        factory.setChannelRpcTimeout(properties.getRpcTimeoutMs());
        factory.setShutdownTimeout(properties.getRpcTimeoutMs());
        // Transport cap also bounds allocations for oversized poison messages.
        // A larger delivery closes this dedicated connection and remains in DLQ.
        factory.setMaxInboundMessageBodySize(properties.getMaxBodyBytes() + 1);
    }

    public String virtualHost() { return factory.getVirtualHost(); }

    @FunctionalInterface interface Work<T> { T run(Session session) throws Exception; }

    <T> Future<T> submit(boolean passive, Work<T> work) {
        if (stopped) throw new RejectedExecutionException();
        return (passive ? observer : operator).submit(() -> {
            Session session = new Session();
            active.add(session);
            ScheduledFuture<?> deadline = null;
            try {
                deadline = timer.schedule(session::cancel, properties.getDeadlineMs(), TimeUnit.MILLISECONDS);
                if (stopped) throw new CancellationException();
                var connectionFactory = factory.clone();
                var socketConfigurator = connectionFactory.getSocketConfigurator();
                connectionFactory.setSocketConfigurator(socket -> {
                    session.attachSocket(socket);
                    socketConfigurator.configure(socket);
                });
                session.attach(connectionFactory.newConnection("notification-dlq-ops"));
                session.check();
                session.channel = session.connection.get().createChannel();
                return work.run(session);
            } finally {
                session.cancel();
                if (deadline != null) deadline.cancel(false);
                active.remove(session);
            }
        });
    }

    static class Session {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Connection> connection = new AtomicReference<>();
        private final AtomicReference<Socket> socket = new AtomicReference<>();
        Channel channel;
        void attachSocket(Socket s) throws IOException {
            socket.set(s);
            if (cancelled.get()) s.close();
        }
        void attach(Connection c) {
            connection.set(c);
            if (cancelled.get()) c.abort(0);
        }
        void check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
        void cancel() {
            cancelled.set(true);
            // Closing the socket first releases a blocked writer; protocol abort
            // alone can wait on that writer's lock during broker/network stalls.
            Socket s = socket.getAndSet(null);
            if (s != null) try { s.close(); } catch (IOException ignored) { }
            Connection c = connection.getAndSet(null);
            if (c != null) c.abort(0);
        }
    }

    private static ExecutorService lane(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> daemon(r, name), new ThreadPoolExecutor.AbortPolicy());
    }
    private static ScheduledThreadPoolExecutor deadlineTimer() {
        var executor = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "notification-dlq-deadline"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    @Override @PreDestroy public void close() {
        stopped = true;
        active.forEach(Session::cancel);
        observer.shutdownNow(); operator.shutdownNow(); timer.shutdownNow();
    }
}
