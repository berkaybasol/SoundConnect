package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClientImpl;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailProducerImpl;
import com.berkayb.soundconnect.shared.mail.producer.MailRetryPublisher;
import com.berkayb.soundconnect.shared.config.MailRetryTopology;
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
            DockerImageName.parse("rabbitmq:4.3.6-management").asCompatibleSubstituteFor("rabbitmq"))
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
    String queue, direct, dlq;
    final List<String> ownedQueues = new ArrayList<>();
    AtomicInteger calls = new AtomicInteger(), acks = new AtomicInteger(), rejects = new AtomicInteger();
    AtomicInteger nextStatus = new AtomicInteger(202);
    java.util.concurrent.atomic.AtomicBoolean holdNextResponse = new java.util.concurrent.atomic.AtomicBoolean();
    CountDownLatch acceptedHeldRequest = new CountDownLatch(1), releaseHeldResponse = new CountDownLatch(1);
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
        queue="fix.mail."+suffix; direct="fix.direct."+suffix; dlq=queue+".dlq";
        first.exchangeDeclare(direct,"direct",true);
        first.queueDeclare(queue,true,false,false,Map.of("x-queue-type","quorum",
                "x-dead-letter-exchange",direct,"x-dead-letter-routing-key","mail.failed",
                "x-dead-letter-strategy","at-least-once","x-overflow","reject-publish","x-delivery-limit",-1));
        first.queueDeclare(dlq,true,false,false,Map.of("x-queue-type","quorum"));
        first.queueBind(dlq,direct,"mail.failed");
        ownedQueues.add(queue); ownedQueues.add(dlq);
        first.queueBind(queue,direct,"mail.send");
        for (long delay : MailRetryTopology.delays()) {
            var retryQueue = MailRetryTopology.queue(queue, delay, direct, "mail.send");
            first.queueDeclare(retryQueue.getName(), true, false, false, retryQueue.getArguments());
            ownedQueues.add(retryQueue.getName());
        }
        producer = new MailProducerImpl(rabbit,helper);
        field(producer,"mailExchange",direct); field(producer,"mailRoutingKey","mail.send");
        field(producer,"confirmTimeoutSec",3L);
        MailRetryPublisher retry = new MailRetryPublisher(rabbit);
        field(retry,"mailQueueName",queue);
        field(retry,"confirmTimeoutSec",3L);
        var init = MailRetryPublisher.class.getDeclaredMethod("validateConfiguration");
        init.setAccessible(true); init.invoke(retry);
        sink = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        sinkWorker=Executors.newFixedThreadPool(2); sink.setExecutor(sinkWorker);
        sink.createContext("/mail",exchange -> {
            // Read full wire content, but persist only safe counts, never the reset code.
            byte[] payload=exchange.getRequestBody().readAllBytes();
            if (!"POST".equals(exchange.getRequestMethod()) || payload.length==0) {
                exchange.sendResponseHeaders(400,-1);
            } else {
                calls.incrementAndGet();
                if (holdNextResponse.compareAndSet(true, false)) {
                    acceptedHeldRequest.countDown();
                    try { releaseHeldResponse.await(8, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                try { exchange.sendResponseHeaders(nextStatus.getAndSet(202),-1); }
                catch (java.io.IOException clientAlreadyTimedOut) { /* Expected for unknown-outcome fixture. */ }
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
        releaseHeldResponse.countDown();
        if (helper!=null) helper.resume.countDown();
        if (connection!=null && connection.isOpen()) {
            try (Channel cleanup=connection.createChannel()) {
                for (String name:ownedQueues) cleanup.queueDelete(name);
                if(direct!=null) cleanup.exchangeDelete(direct);
            } catch(Exception failure) { throw new AssertionError("Owned mail fixture cleanup failed",failure); }
        }
        if (connection!=null) connection.abort();
        if (rabbitFactory!=null) rabbitFactory.destroy();
        if (redisFactory!=null) redisFactory.destroy();
        if (sink!=null) sink.stop(0);
        if (sinkWorker!=null) sinkWorker.shutdownNow();
    }

    @Test void recoveryLosesSentMarkerAndAllowsSameGenericMailAgain() throws Exception {
        MailSendRequest job=request();
        producer.send(job); deliver(receive(first), first);
        String sent="mail:sent:"+helper.buildIdemKey(job);
        assertThat(helper.isAlreadySent(sent)).isTrue();
        producer.send(job); deliver(receive(second), second);
        assertThat(calls).hasValue(1);
        assertThat(redis.getExpire(sent)).isGreaterThan(800L);
        flushOwnedRedis();
        assertThat(helper.isAlreadySent(sent)).isFalse();
        producer.send(job); deliver(receive(first), first);
        assertThat(calls).hasValue(2); assertThat(acks).hasValue(3);
        assertNoUnackedRedelivery();
        recoveryEvidence("generic-sent-loss", Map.of("logicalJobs",1,"loopbackRequests",2,
                "acks",3,"duplicateBeforeLossSuppressed",true,"automaticRecoverySafe",false));
    }

    @Test void recoveryLosesLiveLockWhileFirstLocalHttpSendIsInFlight() throws Exception {
        MailSendRequest job=request(); producer.send(job); producer.send(job);
        Delivery a=receive(first), b=receive(second);
        holdNextResponse.set(true);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlight=worker.submit(() -> deliver(a, first));
            assertThat(acceptedHeldRequest.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(inFlight.isDone()).isFalse();
            String lock="mail:lock:"+helper.buildIdemKey(job);
            assertThat(redis.getExpire(lock)).isGreaterThan(250L);
            assertThat(helper.isAlreadySent("mail:sent:"+helper.buildIdemKey(job))).isFalse();
            flushOwnedRedis();
            deliver(b, second);
            assertThat(calls).hasValue(2);
            assertThat(inFlight.isDone()).isFalse();
            releaseHeldResponse.countDown(); inFlight.get(5,TimeUnit.SECONDS);
            assertThat(acks).hasValue(2); assertThat(rejects).hasValue(0);
            assertNoUnackedRedelivery();
            recoveryEvidence("generic-lock-loss", Map.of("logicalJobs",1,"loopbackRequests",2,
                    "acks",2,"firstSendStillInFlightAtSecondAcceptance",true,"automaticRecoverySafe",false));
        } finally { releaseHeldResponse.countDown(); worker.shutdownNow(); }
    }

    @Test void recoveryUnknownGenericHttpOutcomeRetriesAfterRedisLoss() throws Exception {
        MailSendRequest job=request(); producer.send(job);
        holdNextResponse.set(true);
        deliver(receive(first), first); // Local sink accepted the body; client times out without a response.
        assertThat(acceptedHeldRequest.getCount()).isZero();
        assertThat(calls).hasValue(1); assertThat(acks).hasValue(1);
        assertThat(helper.isAlreadySent("mail:sent:"+helper.buildIdemKey(job))).isFalse();
        releaseHeldResponse.countDown();
        flushOwnedRedis();
        Delivery retry=receive(second);
        assertThat(((Number)retry.headers().get(MailJobHelper.RETRY_ATTEMPT_HEADER)).intValue()).isEqualTo(1);
        deliver(retry, second);
        assertThat(calls).hasValue(2); assertThat(acks).hasValue(2);
        assertThat(helper.isAlreadySent("mail:sent:"+helper.buildIdemKey(job))).isTrue();
        assertNoUnackedRedelivery();
        recoveryEvidence("generic-unknown-outcome", Map.of("logicalJobs",1,"loopbackAcceptedBodies",2,
                "firstResponseUnknownToConsumer",true,"retryAttempt",1,"automaticRecoverySafe",false));
    }

    private void flushOwnedRedis() {
        assertThat(REDIS.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", "notification-fix-mail");
        assertThat(redisFactory.getHostName()).isEqualTo(REDIS.getHost());
        assertThat(redisFactory.getPort()).isEqualTo(REDIS.getMappedPort(6379));
        assertThat(redisFactory.getDatabase()).isZero();
        try (var connection=redisFactory.getConnection()) {
            connection.serverCommands().flushDb();
            assertThat(connection.serverCommands().dbSize()).isZero();
        }
    }

    private void recoveryEvidence(String scenario, Map<String,Object> observations) throws Exception {
        var record=new LinkedHashMap<String,Object>(observations);
        record.put("redisContainer",REDIS.getContainerId()); record.put("rabbitContainer",BROKER.getContainerId());
        record.put("loopbackPort",sink.getAddress().getPort());
        com.berkayb.soundconnect.support.RecoveryCacheMailEvidence.write(scenario,record);
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

    @Test void retryLimitReachedUsesRealDlqWithoutCreatingAnotherRetry() throws Exception {
        MailSendRequest job=request(); nextStatus.set(503);
        rabbit.convertAndSend(direct,"mail.send",job,message -> {
            message.getMessageProperties().setHeader(MailJobHelper.RETRY_ATTEMPT_HEADER,3);
            return message;
        });
        deliver(receive(first),first);
        await().atMost(Duration.ofSeconds(10)).until(() -> first.queueDeclarePassive(dlq).getMessageCount()==1);
        var failed=first.basicGet(dlq,true);
        assertThat(failed.getProps().getHeaders()).containsEntry(MailJobHelper.RETRY_ATTEMPT_HEADER,3);
        assertThat(calls).hasValue(1); assertThat(acks).hasValue(0); assertThat(rejects).hasValue(1);
        assertThat(first.queueDeclarePassive(queue).getMessageCount()).isZero();
        for(long delay:MailRetryTopology.delays()) {
            assertThat(first.queueDeclarePassive(MailRetryTopology.queueName(queue,delay)).getMessageCount()).isZero();
        }
        System.out.println("ACCEPTANCE exhausted: loopbackProvider503=1 terminalDlq=1 retryAttempt=3 newRetry=0");
    }

    @Test void missingRetryLaneKeepsOriginalUnacknowledgedAndRecoverable() throws Exception {
        String retryName=MailRetryTopology.queueName(queue,100);
        first.queueDelete(retryName);
        MailSendRequest job=request(); nextStatus.set(503); producer.send(job);
        deliver(receive(first),first);
        assertThat(calls).hasValue(1); assertThat(acks).hasValue(0); assertThat(rejects).hasValue(1);
        var retryDefinition=MailRetryTopology.queue(queue,100,direct,"mail.send");
        first.queueDeclare(retryName,true,false,false,retryDefinition.getArguments());
        Delivery original=receive(second);
        assertThat(original.request()).isEqualTo(job);
        assertThat(original.headers()).doesNotContainKey(MailJobHelper.RETRY_ATTEMPT_HEADER);
        deliver(original,second);
        assertThat(calls).hasValue(2); assertThat(acks).hasValue(1);
        assertThat(helper.isAlreadySent("mail:sent:"+helper.buildIdemKey(job))).isTrue();
        System.out.println("ACCEPTANCE retryRouteFailure: originalAckBeforeRecovery=0 requeue=1 originalRecovered=true");
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
