package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClientImpl;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailProducerImpl;
import com.berkayb.soundconnect.shared.mail.producer.MailRetryPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Actual Redis/Rabbit/HTTP pipeline; provider is a loopback sink, never an external mailbox. */
@Testcontainers
class MailDeliveryConcurrencyRabbitRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withLabel("soundconnect.fixture", "notification-fix-mail").withExposedPorts(6379);
    @Container static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("soundconnect-rabbitmq:3.13.7").asCompatibleSubstituteFor("rabbitmq"))
            .withLabel("soundconnect.fixture", "notification-fix-mail").withReuse(false);

    LettuceConnectionFactory redisFactory;
    CachingConnectionFactory rabbitFactory;
    StringRedisTemplate redis;
    RabbitTemplate rabbit;
    Connection connection;
    Channel first, second;
    HttpServer sink;
    ExecutorService sinkWorker;
    GatedHelper helper;
    MailProducerImpl producer;
    MailJobConsumer consumer;
    OtpService otp;
    String queue, direct, delayed;
    AtomicInteger calls = new AtomicInteger(), acks = new AtomicInteger(), rejects = new AtomicInteger();
    AtomicInteger nextStatus = new AtomicInteger(202);
    ObjectMapper json = new ObjectMapper();

    @BeforeEach void prepare() throws Exception {
        redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        helper = new GatedHelper(redis);
        rabbitFactory = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
        rabbitFactory.setUsername(BROKER.getAdminUsername()); rabbitFactory.setPassword(BROKER.getAdminPassword());
        rabbitFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitFactory.setPublisherReturns(true);
        rabbit = new RabbitTemplate(rabbitFactory);
        rabbit.setMessageConverter(new Jackson2JsonMessageConverter()); rabbit.setMandatory(true);
        connection = rabbitFactory.getRabbitConnectionFactory().newConnection();
        first = connection.createChannel(); second = connection.createChannel();
        String suffix = UUID.randomUUID().toString();
        queue="fix.mail."+suffix; direct="fix.direct."+suffix; delayed="fix.delayed."+suffix;
        first.exchangeDeclare(direct,"direct",true);
        first.exchangeDeclare(delayed,"x-delayed-message",true,false,Map.of("x-delayed-type","direct"));
        first.queueDeclare(queue,true,false,false,Map.of());
        first.queueBind(queue,direct,"mail.send"); first.queueBind(queue,delayed,"mail.send");
        producer = new MailProducerImpl(rabbit,helper);
        field(producer,"mailExchange",direct); field(producer,"mailRoutingKey","mail.send");
        field(producer,"confirmTimeoutSec",3L);
        MailRetryPublisher retry = new MailRetryPublisher(rabbit);
        field(retry,"mailQueueName",queue); field(retry,"delayedExchange",delayed);
        field(retry,"routingKey","mail.send"); field(retry,"confirmTimeoutSec",3L);
        var init = MailRetryPublisher.class.getDeclaredMethod("validateConfiguration");
        init.setAccessible(true); init.invoke(retry);
        sink = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        sinkWorker=Executors.newSingleThreadExecutor(); sink.setExecutor(sinkWorker);
        sink.createContext("/mail",exchange -> {
            // Read full wire content, but persist only safe counts, never the reset code.
            byte[] payload=exchange.getRequestBody().readAllBytes();
            if (!"POST".equals(exchange.getRequestMethod()) || payload.length==0) {
                exchange.sendResponseHeaders(400,-1);
            } else {
                calls.incrementAndGet(); exchange.sendResponseHeaders(nextStatus.getAndSet(202),-1);
            }
            exchange.close();
        });
        sink.start();
        var sender = new MailSenderClientImpl(helper, WebClient.builder()
                .baseUrl("http://127.0.0.1:"+sink.getAddress().getPort()+"/mail").build());
        field(sender,"fromEmail","fixture@example.invalid"); field(sender,"fromName","Fixture");
        field(sender,"readTimeoutSec",3);
        consumer = new MailJobConsumer(sender,helper,retry,null);
        field(consumer,"idempotencyTtlSec",900L); field(consumer,"lockTtlSec",300L);
        field(consumer,"maxRedeliveries",3); field(consumer,"delaysMs",List.of(100L));
        field(consumer,"useRetryAfter",true);
        otp = new OtpService(redis);
        field(otp,"otpExpiredMinutes",1L); field(otp,"otpLength",6);
        field(otp,"maxAttempt",5); field(otp,"resendCoolDownSeconds",1L);
        consumer.setOtpService(otp);
    }

    @AfterEach void close() {
        if (helper!=null) helper.resume.countDown();
        if (connection!=null) connection.abort();
        if (rabbitFactory!=null) rabbitFactory.destroy();
        if (redisFactory!=null) redisFactory.destroy();
        if (sink!=null) sink.stop(0);
        if (sinkWorker!=null) sinkWorker.shutdownNow();
    }

    @Test void concurrentBrokerCopiesSendOnceAndAcknowledgeBoth() throws Exception {
        MailSendRequest job=request(); producer.send(job); producer.send(job);
        Delivery a=receive(first), b=receive(second);
        ExecutorService worker=Executors.newSingleThreadExecutor(task -> new Thread(task,"second-mail"));
        try {
            Future<?> duplicate=worker.submit(() -> deliver(b,second));
            assertThat(helper.read.await(5,TimeUnit.SECONDS)).isTrue();
            deliver(a,first);
            assertThat(calls).hasValue(1);
            assertThat(helper.isAlreadySent("mail:sent:"+helper.buildIdemKey(job))).isTrue();
            helper.resume.countDown(); duplicate.get(5,TimeUnit.SECONDS);
            assertThat(calls).hasValue(1); assertThat(acks).hasValue(2); assertThat(rejects).hasValue(0);
            assertThat(redis.hasKey("mail:lock:"+helper.buildIdemKey(job))).isFalse();
            assertNoUnackedRedelivery();
            System.out.println("ACCEPTANCE concurrent: provider=1 ack=2 recovered=0 redisSent=true lock=false");
        } finally { helper.resume.countDown(); worker.shutdownNow(); }
    }

    @Test void sequentialDuplicateAndBusyLockPreserveSourceDelivery() throws Exception {
        MailSendRequest job=request(); producer.send(job); producer.send(job);
        deliver(receive(first),first); deliver(receive(second),second);
        assertThat(calls).hasValue(1); assertThat(acks).hasValue(2);
        MailSendRequest busy=request(); producer.send(busy);
        String lock="mail:lock:"+helper.buildIdemKey(busy);
        assertThat(helper.acquireLock(lock,Duration.ofSeconds(30))).isTrue();
        deliver(receive(first),first);
        assertThat(calls).hasValue(1); assertThat(acks).hasValue(2); assertThat(rejects).hasValue(1);
        assertThat(redis.hasKey(lock)).isTrue();
        helper.releaseLock(lock);
        deliver(receive(second),second);
        assertThat(calls).hasValue(2); assertThat(acks).hasValue(3);
        assertNoUnackedRedelivery();
        System.out.println("ACCEPTANCE sequential+busy: provider=2 distinctJobs=2 ack=3 requeue=1 recovered=0");
    }

    @ParameterizedTest @ValueSource(ints = {307, 429, 503})
    void failedProviderUsesDelayedRetryThenSentMarkerSuppressesCopy(int initialStatus) throws Exception {
        MailSendRequest job=request(); nextStatus.set(initialStatus); producer.send(job);
        deliver(receive(first),first);
        assertThat(calls).hasValue(1); assertThat(acks).hasValue(1);
        assertThat(helper.isAlreadySent("mail:sent:"+helper.buildIdemKey(job))).isFalse();
        Delivery retry=receive(second);
        assertThat(((Number)retry.headers().get(MailJobHelper.RETRY_ATTEMPT_HEADER)).intValue()).isEqualTo(1);
        deliver(retry,second);
        producer.send(job); deliver(receive(first),first);
        assertThat(calls).hasValue(2); assertThat(acks).hasValue(3);
        assertThat(helper.isAlreadySent("mail:sent:"+helper.buildIdemKey(job))).isTrue();
        assertNoUnackedRedelivery();
        System.out.println("ACCEPTANCE retry: provider"+initialStatus+"=1 provider202=1 retryAttempt=1 ack=3 duplicateSuppressed=true");
    }

    @Test void staleResetIsDiscardedAndCurrentResetRemainsUsable() throws Exception {
        String recipient="reset-"+UUID.randomUUID()+"@example.invalid";
        var old=otp.acquirePasswordResetOtp(recipient);
        assertThat(otp.cancelPasswordResetOtpIssue(recipient,old.generationId())).isTrue();
        var current=otp.acquirePasswordResetOtp(recipient);
        assertThat(current.acquired()).isTrue();
        producer.send(reset(recipient,old)); producer.send(reset(recipient,current));
        deliver(receive(first),first);
        assertThat(calls).hasValue(0); assertThat(acks).hasValue(1);
        deliver(receive(second),second);
        assertThat(calls).hasValue(1); assertThat(acks).hasValue(2);
        assertThat(otp.verifyPasswordResetOtp(recipient,current.code())).isTrue();
        assertNoUnackedRedelivery();
        System.out.println("ACCEPTANCE reset: staleProvider=0 currentProvider=1 ack=2 currentCodeVerified=true");
    }

    private MailSendRequest request() { return new MailSendRequest("fixture@example.invalid","Fixture","html","text",MailKind.GENERIC,Map.of("requestId",UUID.randomUUID().toString())); }
    private MailSendRequest reset(String recipient,OtpService.OtpIssueClaim claim) {
        return new MailSendRequest(recipient,"Reset fixture","html","text",MailKind.PASSWORD_RESET,
                Map.of("requestId",claim.generationId(),"claimRecipient",recipient,"expiresAtEpochMillis",claim.expiresAtEpochMillis()));
    }
    private Delivery receive(Channel channel) throws Exception {
        await().atMost(Duration.ofSeconds(5)).until(() -> channel.queueDeclarePassive(queue).getMessageCount()>0);
        var message=channel.basicGet(queue,false);
        assertThat(message).isNotNull();
        return new Delivery(json.readValue(message.getBody(),MailSendRequest.class),message.getEnvelope().getDeliveryTag(),
                message.getProps().getHeaders()==null ? Map.of() : message.getProps().getHeaders());
    }
    private void deliver(Delivery delivery,Channel channel) {
        Channel observed=(Channel)Proxy.newProxyInstance(Channel.class.getClassLoader(),new Class<?>[]{Channel.class},(proxy,method,args) -> {
            try {
                Object result=method.invoke(channel,args);
                if(method.getName().equals("basicAck")) acks.incrementAndGet();
                if(method.getName().equals("basicReject")) rejects.incrementAndGet();
                return result;
            } catch(InvocationTargetException failure) { throw failure.getCause(); }
        });
        consumer.listenMailJobs(delivery.request(),delivery.tag(),delivery.headers(),observed);
    }
    private void assertNoUnackedRedelivery() throws Exception {
        first.queueDeclarePassive(queue); second.queueDeclarePassive(queue);
        first.close(); second.close();
        try(Channel observer=connection.createChannel()) { assertThat(observer.queueDeclarePassive(queue).getMessageCount()).isZero(); }
    }
    private static void field(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    private record Delivery(MailSendRequest request,long tag,Map<String,Object> headers) { }
    private static class GatedHelper extends MailJobHelper {
        final CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);
        GatedHelper(StringRedisTemplate redis) { super(redis); }
        @Override public boolean isAlreadySent(String key) {
            boolean snapshot=super.isAlreadySent(key);
            if(Thread.currentThread().getName().equals("second-mail") && !snapshot) {
                read.countDown();
                try { if(!resume.await(5,TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out"); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }
            return snapshot;
        }
    }
}
