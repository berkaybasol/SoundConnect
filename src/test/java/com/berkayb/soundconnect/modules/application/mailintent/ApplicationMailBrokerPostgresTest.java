package com.berkayb.soundconnect.modules.application.mailintent;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailProducerImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual PG + broker confirms/returns/persistent wire payload. Faulted NACK/timeout are explicitly simulated. */
@Testcontainers
class ApplicationMailBrokerPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil011_broker").withUsername("bil011").withPassword("bil011");
    @Container static final RabbitMQContainer MQ=new RabbitMQContainer("rabbitmq:3.13-alpine");
    NamedParameterJdbcTemplate jdbc; ApplicationMailIntentStore store; ApplicationMailProperties properties;
    CachingConnectionFactory connection; RabbitTemplate rabbit; RabbitAdmin admin; MailProducerImpl producer;
    String exchange, queue; UUID app;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-10-06-application-mail-intents.sql")).execute(ds);
        jdbc=new NamedParameterJdbcTemplate(ds); jdbc.getJdbcTemplate().execute("DELETE FROM tbl_application_mail_intent");
        var tm=new DataSourceTransactionManager(ds); properties=new ApplicationMailProperties();
        store=new ApplicationMailIntentStore(jdbc,new ObjectMapper(),tm,properties);
        connection=new CachingConnectionFactory(MQ.getHost(),MQ.getAmqpPort());
        connection.setUsername(MQ.getAdminUsername()); connection.setPassword(MQ.getAdminPassword());
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); connection.setPublisherReturns(true);
        rabbit=new RabbitTemplate(connection); rabbit.setMandatory(true); rabbit.setMessageConverter(new Jackson2JsonMessageConverter());
        admin=new RabbitAdmin(connection); exchange="bil011."+UUID.randomUUID(); queue=exchange+".queue";
        admin.declareExchange(new DirectExchange(exchange,true,false)); admin.declareQueue(new org.springframework.amqp.core.Queue(queue,true));
        producer=producer(rabbit); app=UUID.randomUUID();
        new TransactionTemplate(tm).executeWithoutResult(s->store.enqueue(app,"CREATED",new MailSendRequest("fixture@example.test","Subject",null,"Private fixture",MailKind.VENUE_APPLICATION_ADMIN,Map.of("applicationId",app.toString()))));
    }
    @AfterEach void close() { connection.destroy(); }
    MailProducerImpl producer(RabbitTemplate template) {
        var p=new MailProducerImpl(template,new MailJobHelper(null));
        ReflectionTestUtils.setField(p,"mailExchange",exchange); ReflectionTestUtils.setField(p,"mailRoutingKey","send");
        ReflectionTestUtils.setField(p,"confirmTimeoutSec",1L); return p;
    }
    void bind() { admin.declareBinding(new Binding(queue,Binding.DestinationType.QUEUE,exchange,"send",null)); }
    ApplicationMailDispatcher dispatcher(MailProducerImpl p) { return new ApplicationMailDispatcher(store,p,properties,Runnable::run); }
    @Test void actualReturnedMessageIsRetryableAndRestartWithRoutePublishesPersistentMessage() {
        dispatcher(producer).dispatchBatch();
        assertThat(state()).isEqualTo("PENDING"); assertThat(rabbit.receive(queue)).isNull();
        bind(); due(); dispatcher(producer).dispatchBatch();
        assertThat(state()).isEqualTo("PUBLISHED");
        var message=rabbit.receive(queue,5000); assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8)).contains(app.toString(),"applicationMailIntentId");
        assertThat(admin.getQueueInfo(queue).getMessageCount()).isZero();
    }
    @Test void realConfirmThenLostOutcomeCreatesTwoMessagesWithSameSnapshotAndDedupIdentity() {
        bind(); var claim=store.claim().orElseThrow(); var request=store.payload(claim); producer.send(request);
        assertThat(state()).isEqualTo("PUBLISHING");
        jdbc.getJdbcTemplate().execute("UPDATE tbl_application_mail_intent SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        dispatcher(producer).dispatchBatch(); assertThat(state()).isEqualTo("PUBLISHED");
        assertThat(store.published(claim)).isFalse();
        var one=(MailSendRequest)rabbit.receiveAndConvert(queue,5000); var two=(MailSendRequest)rabbit.receiveAndConvert(queue,5000);
        assertThat(one).isEqualTo(two).isEqualTo(request);
        var helper=new MailJobHelper(null); assertThat(helper.buildIdemKey(one)).isEqualTo(helper.buildIdemKey(two));
    }
    @ParameterizedTest @ValueSource(strings={"NACK","TIMEOUT","RETURN"})
    void simulatedConfirmFaultNeverMarksIntentPublished(String fault) {
        var wire=mock(RabbitTemplate.class);
        doAnswer(inv->{
            CorrelationData cd=inv.getArgument(3);
            if(fault.equals("RETURN")) cd.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"private provider body",exchange,"send"));
            if(!fault.equals("TIMEOUT")) cd.getFuture().complete(new CorrelationData.Confirm(!fault.equals("NACK"),"safe-test"));
            return null;
        }).when(wire).convertAndSend(anyString(),anyString(),any(MailSendRequest.class),any(CorrelationData.class));
        dispatcher(producer(wire)).dispatchBatch();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error FROM tbl_application_mail_intent",Map.of(),String.class)).isEqualTo("publish_failed_or_unknown");
    }
    void due() { jdbc.getJdbcTemplate().execute("UPDATE tbl_application_mail_intent SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'"); }
    String state() { return jdbc.queryForObject("SELECT status FROM tbl_application_mail_intent",Map.of(),String.class); }
}
