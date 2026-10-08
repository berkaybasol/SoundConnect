package com.berkayb.soundconnect.modules.application.mailintent;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class ApplicationMailIntentPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil011_store").withUsername("bil011").withPassword("bil011");
    DriverManagerDataSource ds;
    NamedParameterJdbcTemplate jdbc;
    ApplicationMailIntentStore store;
    ApplicationMailProperties properties;
    TransactionTemplate tx;
    MailProducer producer;
    ApplicationMailDispatcher dispatcher;
    @BeforeEach void setup() {
        ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        jdbc=new NamedParameterJdbcTemplate(ds);
        migrate();
        jdbc.getJdbcTemplate().execute("DELETE FROM tbl_application_mail_intent");
        jdbc.getJdbcTemplate().execute("CREATE TABLE IF NOT EXISTS fixture_business(id uuid primary key)");
        jdbc.getJdbcTemplate().execute("DELETE FROM fixture_business");
        properties=new ApplicationMailProperties(); properties.setMaxAttempts(3); properties.setRetryBaseSeconds(1);
        var tm=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(tm);
        store=new ApplicationMailIntentStore(jdbc,new ObjectMapper(),tm,properties);
        producer=mock(MailProducer.class);
        dispatcher=new ApplicationMailDispatcher(store,producer,properties,Runnable::run);
    }
    void migrate() { new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-10-06-application-mail-intents.sql")).execute(ds); }
    @ParameterizedTest @EnumSource(value=MailKind.class,names={"VENUE_APPLICATION_ADMIN","STUDIO_APPLICATION_ADMIN","STUDIO_APPLICATION_DECISION"})
    void businessAndIntentShareCommitRollbackAndInsertFailure(MailKind kind) {
        UUID id=UUID.randomUUID();
        tx.executeWithoutResult(s->{ business(id); enqueue(id,kind,"one@example.test"); s.setRollbackOnly(); });
        assertThat(count("fixture_business")).isZero(); assertThat(count("tbl_application_mail_intent")).isZero();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{ business(id); enqueue(id,kind,"x".repeat(321)); }))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageNotContaining("x".repeat(321)).hasMessageNotContaining("Fixture body").hasNoCause();
        assertThat(count("fixture_business")).isZero(); assertThat(count("tbl_application_mail_intent")).isZero();
        tx.executeWithoutResult(s->{ business(id); enqueue(id,kind,"one@example.test"); });
        assertThat(count("fixture_business")).isOne(); assertThat(count("tbl_application_mail_intent")).isOne();
        verifyNoInteractions(producer);
    }
    @Test void enqueueWithoutBusinessTransactionIsRejected() {
        assertThatThrownBy(()->enqueue(UUID.randomUUID(),MailKind.VENUE_APPLICATION_ADMIN,"one@example.test"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(count("tbl_application_mail_intent")).isZero();
    }
    @Test void concurrentDuplicateRequestsKeepOriginalSnapshotIdentityAndRecipientPurposeSiblings() throws Exception {
        UUID id=UUID.randomUUID();
        parallel(8,()->tx.executeWithoutResult(s->enqueue(id,MailKind.STUDIO_APPLICATION_ADMIN,"one@example.test")));
        var first=jdbc.queryForMap("SELECT id,payload FROM tbl_application_mail_intent",Map.of());
        tx.executeWithoutResult(s->{
            var changed=request(id,MailKind.STUDIO_APPLICATION_ADMIN,"one@example.test");
            store.enqueue(id,"CREATED",new MailSendRequest(changed.to(),"new subject",null,"new body",changed.kind(),changed.params()));
            enqueue(id,MailKind.STUDIO_APPLICATION_ADMIN,"two@example.test");
            enqueue(id,MailKind.STUDIO_APPLICATION_DECISION,"one@example.test");
            enqueue(UUID.randomUUID(),MailKind.STUDIO_APPLICATION_ADMIN,"one@example.test");
        });
        assertThat(count("tbl_application_mail_intent")).isEqualTo(4);
        assertThat(jdbc.queryForMap("SELECT id,payload FROM tbl_application_mail_intent WHERE id=:id",Map.of("id",first.get("id"))))
                .isEqualTo(first);
        parallel(4,dispatcher::dispatchBatch);
        assertThat(number("SELECT count(*) FROM tbl_application_mail_intent WHERE status='PUBLISHED'")).isEqualTo(4);
        verify(producer,times(4)).send(any());
    }
    @Test void snapshotSurvivesCallerMutationAndRestart() {
        UUID id=UUID.randomUUID(); var original=request(id,MailKind.STUDIO_APPLICATION_DECISION,"one@example.test");
        var params=new HashMap<>(original.params());
        tx.executeWithoutResult(s->store.enqueue(id,"APPROVED",new MailSendRequest(original.to(),original.subject(),null,"snapshot",original.kind(),params)));
        params.put("status","REJECTED"); params.put("private","changed");
        var restarted=new ApplicationMailIntentStore(jdbc,new ObjectMapper(),new DataSourceTransactionManager(ds),properties);
        var claim=restarted.claim().orElseThrow(); var payload=restarted.payload(claim);
        assertThat(payload.params()).containsEntry("status","APPROVED").doesNotContainKey("private");
        assertThat(payload.textBody()).isEqualTo("snapshot");
        assertThat(claim.toString()).doesNotContain("snapshot","example.test");
    }
    @Test void activeLeaseFuturePendingAndExpiredLeaseAreFenced() {
        accept(); var old=store.claim().orElseThrow();
        assertThat(store.claim()).isEmpty();
        expire(old.id());
        assertThat(store.published(old)).isFalse(); assertThat(store.failed(old,false)).isFalse();
        var current=store.claim().orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(store.published(old)).isFalse(); assertThat(store.failed(old,false)).isFalse();
        assertThat(store.published(current)).isTrue();
        accept(); jdbc.getJdbcTemplate().execute("UPDATE tbl_application_mail_intent SET next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '1 hour' WHERE status='PENDING'");
        assertThat(store.claim()).isEmpty();
        assertThat(number("SELECT sum(attempt_count) FROM tbl_application_mail_intent WHERE status='PENDING'")).isZero();
    }
    @Test void lastAttemptRunsThenExhaustedDueAndExpiredRecoverWithoutTouchingActiveOrFuture() {
        for(int i=0;i<5;i++) accept();
        var ids=jdbc.queryForList("SELECT id FROM tbl_application_mail_intent ORDER BY created_at,id",Map.of(),UUID.class);
        update(ids.get(0),"attempt_count=2");
        update(ids.get(1),"attempt_count=3");
        update(ids.get(2),"attempt_count=3,status='PUBLISHING',lease_token='"+UUID.randomUUID()+"',lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        update(ids.get(3),"attempt_count=3,status='PUBLISHING',lease_token='"+UUID.randomUUID()+"',lease_until=CURRENT_TIMESTAMP+INTERVAL '1 hour'");
        update(ids.get(4),"attempt_count=3,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '1 hour'");
        var last=store.claim().orElseThrow(); assertThat(last.id()).isEqualTo(ids.get(0)); assertThat(last.attempt()).isEqualTo(3);
        assertThat(store.claim()).isEmpty();
        assertThat(status(ids.get(1))).isEqualTo("NEEDS_REVIEW"); assertThat(status(ids.get(2))).isEqualTo("NEEDS_REVIEW");
        assertThat(status(ids.get(3))).isEqualTo("PUBLISHING"); assertThat(status(ids.get(4))).isEqualTo("PENDING");
        assertThat(store.failed(last,false)).isTrue(); assertThat(status(last.id())).isEqualTo("NEEDS_REVIEW");
        assertThat(number("SELECT max(attempt_count) FROM tbl_application_mail_intent")).isEqualTo(3);
        assertThat(new ApplicationMailHealthIndicator(store).health().getStatus().getCode()).isEqualTo("DOWN");
    }
    @Test void brokerFailureRetriesSameIdentityAndPayloadWithBoundedBackoff() {
        accept(); doThrow(new IllegalStateException("secret@example.test private provider response")).when(producer).send(any());
        dispatcher.dispatchBatch();
        assertThat(number("SELECT count(*) FROM tbl_application_mail_intent WHERE status='PENDING' AND attempt_count=1 AND next_attempt_at>CURRENT_TIMESTAMP")).isOne();
        assertThat(store.healthCounts()).containsEntry("retry",1L);
        for(int i=0;i<2;i++) { due(); dispatcher.dispatchBatch(); }
        var captor=org.mockito.ArgumentCaptor.forClass(MailSendRequest.class); verify(producer,times(3)).send(captor.capture());
        assertThat(new HashSet<>(captor.getAllValues())).hasSize(1);
        assertThat(number("SELECT count(*) FROM tbl_application_mail_intent WHERE status='NEEDS_REVIEW' AND last_error='publish_attempt_limit'")).isOne();
        dispatcher.dispatchBatch(); verifyNoMoreInteractions(producer);
    }
    @Test void invalidSnapshotAndOutcomeWriteFailureDoNotBlockSibling() {
        accept(); var bad=jdbc.queryForObject("SELECT id FROM tbl_application_mail_intent",Map.of(),UUID.class);
        update(bad,"payload='{}'::jsonb"); accept();
        dispatcher.dispatchBatch(); assertThat(status(bad)).isEqualTo("NEEDS_REVIEW"); verify(producer).send(any());
        accept(); accept(); var spyStore=spy(store); doThrow(new IllegalStateException("outcome DB unavailable")).doCallRealMethod().when(spyStore).published(any());
        new ApplicationMailDispatcher(spyStore,producer,properties,Runnable::run).dispatchBatch();
        assertThat(number("SELECT count(*) FROM tbl_application_mail_intent WHERE status='PUBLISHING'")).isOne();
        assertThat(number("SELECT count(*) FROM tbl_application_mail_intent WHERE status='PUBLISHED'")).isEqualTo(2);
    }
    @Test void confirmThenLostOutcomeRepublishesWithExactlySameExistingDedupKey() {
        accept(); var claim=store.claim().orElseThrow(); var first=store.payload(claim); producer.send(first);
        // Deterministic process-loss boundary: confirmed send, no outcome transaction, expired lease + new store.
        expire(claim.id());
        var recovered=new ApplicationMailIntentStore(jdbc,new ObjectMapper(),new DataSourceTransactionManager(ds),properties);
        var next=recovered.claim().orElseThrow(); var second=recovered.payload(next);
        var helper=new MailJobHelper(mock(org.springframework.data.redis.core.StringRedisTemplate.class));
        assertThat(second).isEqualTo(first); assertThat(helper.buildIdemKey(second)).isEqualTo(helper.buildIdemKey(first));
        assertThat(store.published(claim)).isFalse(); assertThat(recovered.published(next)).isTrue();
    }
    @Test void invalidSnapshotThroughJpaRepositoryTranslationStopsOnFirstAttemptAndPreservesSibling() {
        accept(); var bad=jdbc.queryForObject("SELECT id FROM tbl_application_mail_intent",Map.of(),UUID.class);
        update(bad,"payload='{}'::jsonb"); accept();
        var proxy=new org.springframework.aop.framework.ProxyFactory(store); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
                new org.springframework.orm.jpa.vendor.HibernateJpaDialect()));
        var advised=(ApplicationMailIntentStore)proxy.getProxy();
        new ApplicationMailDispatcher(advised,producer,properties,Runnable::run).dispatchBatch();
        assertThat(status(bad)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForMap("SELECT attempt_count,last_error FROM tbl_application_mail_intent WHERE id=:id",Map.of("id",bad)))
                .containsEntry("attempt_count",1).containsEntry("last_error","invalid_snapshot");
        assertThat(number("SELECT count(*) FROM tbl_application_mail_intent WHERE status='PUBLISHED'")).isOne();
        verify(producer).send(any());
    }
    @Test void repeatedMigrationPreservesExistingRowsAndMissingSchemaIsUnhealthyAndFailsBusinessTransaction() {
        accept(); var before=jdbc.queryForList("SELECT * FROM tbl_application_mail_intent",Map.of()); migrate(); migrate();
        assertThat(jdbc.queryForList("SELECT * FROM tbl_application_mail_intent",Map.of())).isEqualTo(before);
        jdbc.getJdbcTemplate().execute("ALTER TABLE tbl_application_mail_intent RENAME TO fixture_preserved_intents");
        try {
            assertThat(new ApplicationMailHealthIndicator(store).health().getStatus().getCode()).isEqualTo("DOWN");
            assertThatThrownBy(()->tx.executeWithoutResult(s->{ var id=UUID.randomUUID(); business(id); enqueue(id,MailKind.VENUE_APPLICATION_ADMIN,"one@example.test"); }))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("fixture_business")).isZero();
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE fixture_preserved_intents RENAME TO tbl_application_mail_intent"); }
    }
    void business(UUID id) { jdbc.update("INSERT INTO fixture_business VALUES (:id)",Map.of("id",id)); }
    void accept() { tx.executeWithoutResult(s->enqueue(UUID.randomUUID(),MailKind.VENUE_APPLICATION_ADMIN,"one@example.test")); }
    void enqueue(UUID id,MailKind kind,String to) { store.enqueue(id,kind==MailKind.STUDIO_APPLICATION_DECISION?"APPROVED":"CREATED",request(id,kind,to)); }
    MailSendRequest request(UUID id,MailKind kind,String to) { return new MailSendRequest(to,"Fixture subject",null,"Fixture body",kind,
            Map.of("applicationId",id.toString(),"status",kind==MailKind.STUDIO_APPLICATION_DECISION?"APPROVED":"PENDING")); }
    void update(UUID id,String sql) { jdbc.update("UPDATE tbl_application_mail_intent SET "+sql+" WHERE id=:id",Map.of("id",id)); }
    void expire(UUID id) { update(id,"lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'"); }
    void due() { jdbc.getJdbcTemplate().execute("UPDATE tbl_application_mail_intent SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE status='PENDING'"); }
    String status(UUID id) { return jdbc.queryForObject("SELECT status FROM tbl_application_mail_intent WHERE id=:id",Map.of("id",id),String.class); }
    long count(String table) { return number("SELECT count(*) FROM "+table); }
    long number(String sql) { return jdbc.queryForObject(sql,Map.of(),Long.class); }
    static void parallel(int n,Runnable action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(n)) { var tasks=new ArrayList<Future<?>>(); for(int i=0;i<n;i++) tasks.add(pool.submit(action)); for(var task:tasks) task.get(30,TimeUnit.SECONDS); }
    }
}
