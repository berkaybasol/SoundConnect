package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real lock/source predicates against an explicitly isolated disposable database. */
@Testcontainers
class NotificationErasureBoundaryPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("delivery_erasure_test").withUsername("delivery_test").withPassword("delivery_test").withReuse(false);
    JdbcTemplate jdbc; TransactionTemplate transaction; AccountDeliveryFence accounts;
    NotificationDeliveryPolicy policy; AfterCommitDeliveryExecutor executor;
    UUID recipient, requester, post, request, notification;

    @BeforeEach void setup() throws Exception {
        var dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        try(var connection=dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo("delivery_erasure_test");
        }
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("create table if not exists tbl_user(id uuid primary key, status text, email_verified boolean, erased_at timestamp)");
        jdbc.execute("create table if not exists tbl_overthinking_post(id uuid primary key)");
        jdbc.execute("create table if not exists tbl_overthinking_reveal_request(id uuid primary key,post_id uuid,status text,requester_id uuid,author_id uuid)");
        jdbc.execute("create table if not exists tbl_notification(id uuid primary key,recipient_id uuid)");
        jdbc.execute("truncate tbl_notification,tbl_overthinking_reveal_request,tbl_overthinking_post,tbl_user");
        var manager=new DataSourceTransactionManager(dataSource);
        transaction=new TransactionTemplate(manager);
        var named=new NamedParameterJdbcTemplate(dataSource);
        accounts=new AccountDeliveryFence(named); executor=new AfterCommitDeliveryExecutor();
        policy=new NotificationDeliveryPolicy(accounts,named,manager,executor);
        recipient=UUID.randomUUID(); requester=UUID.randomUUID(); post=UUID.randomUUID();
        request=UUID.randomUUID(); notification=UUID.randomUUID();
        jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null),(?,'ACTIVE',true,null)",recipient,requester);
        jdbc.update("insert into tbl_overthinking_post values (?)",post);
        jdbc.update("insert into tbl_overthinking_reveal_request values (?,?,'PENDING',?,?)",request,post,requester,recipient);
        jdbc.update("insert into tbl_notification values (?,?)",notification,recipient);
    }
    @AfterEach void close() { executor.close(); }

    NotificationInboundEvent event() {
        return new NotificationInboundEvent(UUID.randomUUID(),recipient,NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED,
                "Old title","Old message",Map.of("postId",post.toString(),"revealRequestId",request.toString(),
                        "requesterId",requester.toString()),false,Instant.now());
    }
    boolean eligible() { return Boolean.TRUE.equals(transaction.execute(status -> policy.eligible(event()))); }

    @Test void disabledOrUnverifiedPeerDoesNotInvalidateTheActiveCallersSession() {
        jdbc.update("update tbl_user set status='INACTIVE' where id=?",requester);
        var inactive=catchThrowableOfType(() -> transaction.executeWithoutResult(status ->
                accounts.requireActive(List.of(recipient,requester))),SoundConnectException.class);
        assertThat(inactive.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
        jdbc.update("update tbl_user set status='ACTIVE',email_verified=false where id=?",requester);
        var unverified=catchThrowableOfType(() -> transaction.executeWithoutResult(status ->
                accounts.requireActive(List.of(recipient,requester))),SoundConnectException.class);
        assertThat(unverified.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
    }

    @Test void erasedRecipientOrActorAndMissingOrDecidedRequestCannotBeDelivered() {
        assertThat(eligible()).isTrue();
        jdbc.update("update tbl_user set erased_at=now(),status='INACTIVE' where id=?",requester);
        assertThat(eligible()).isFalse();
        assertThatCode(() -> transaction.executeWithoutResult(status ->
                accounts.requireConversationReader(recipient,List.of(recipient,requester)))).doesNotThrowAnyException();
        assertThat(catchThrowableOfType(() -> transaction.executeWithoutResult(status ->
                accounts.requireActive(List.of(recipient,requester))),SoundConnectException.class).getErrorType())
                .isEqualTo(ErrorType.ACCOUNT_DELETED);
        jdbc.update("update tbl_user set erased_at=null,status='ACTIVE' where id=?",requester);
        jdbc.update("update tbl_user set erased_at=now(),status='INACTIVE' where id=?",recipient);
        assertThat(eligible()).isFalse();
        jdbc.update("update tbl_user set erased_at=null,status='ACTIVE' where id=?",recipient);
        jdbc.update("update tbl_overthinking_reveal_request set status='APPROVED' where id=?",request);
        assertThat(eligible()).isFalse();
        jdbc.update("delete from tbl_overthinking_reveal_request where id=?",request);
        assertThat(eligible()).isFalse();
    }

    @Test void committedButQueuedSnapshotIsSuppressedWhenSourceOrAccountDisappearsBeforeWorkerRuns() throws Exception {
        for(boolean eraseAccount:List.of(false,true)) {
            var blocked=new CountDownLatch(1); var release=new CountDownLatch(1); var drained=new CountDownLatch(1);
            var deliveries=new AtomicInteger();
            executor.submit(() -> { blocked.countDown(); await(release); });
            assertThat(blocked.await(5,TimeUnit.SECONDS)).isTrue();
            policy.schedule(event(),notification,deliveries::incrementAndGet);
            if(eraseAccount) jdbc.update("update tbl_user set erased_at=now() where id=?",requester);
            else jdbc.update("delete from tbl_overthinking_post where id=?",post);
            executor.submit(drained::countDown); release.countDown();
            assertThat(drained.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(deliveries).hasValue(0);
            if(!eraseAccount) jdbc.update("insert into tbl_overthinking_post values (?)",post);
        }
    }

    @Test void activeDeliveryHoldsAccountFenceUntilItFinishesThenErasurePreventsLaterDelivery() throws Exception {
        var admitted=new CountDownLatch(1); var release=new CountDownLatch(1); var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var delivery=pool.submit(() -> transaction.executeWithoutResult(status -> {
                assertThat(policy.eligible(event())).isTrue(); admitted.countDown(); await(release);
            }));
            assertThat(admitted.await(5,TimeUnit.SECONDS)).isTrue();
            var erasure=pool.submit(() -> { started.countDown(); jdbc.update("update tbl_user set erased_at=now() where id=?",requester); });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> erasure.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            delivery.get(5,TimeUnit.SECONDS); erasure.get(5,TimeUnit.SECONDS);
            assertThat(eligible()).isFalse();
        }
    }

    @Test void validatedSourceLockSerializesPostDeletionBeforeAnyLaterReceiptCanBeCreated() throws Exception {
        var admitted=new CountDownLatch(1); var release=new CountDownLatch(1); var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var delivery=pool.submit(() -> transaction.executeWithoutResult(status -> {
                assertThat(policy.eligible(event())).isTrue(); admitted.countDown(); await(release);
            }));
            assertThat(admitted.await(5,TimeUnit.SECONDS)).isTrue();
            var deletion=pool.submit(() -> { started.countDown(); jdbc.update("delete from tbl_overthinking_post where id=?",post); });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> deletion.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            delivery.get(5,TimeUnit.SECONDS); deletion.get(5,TimeUnit.SECONDS);
            assertThat(eligible()).isFalse();
        }
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
