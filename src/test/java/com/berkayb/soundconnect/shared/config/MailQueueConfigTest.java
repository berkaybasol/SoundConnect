package com.berkayb.soundconnect.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MailQueueConfigTest {
	
	private static void set(Object target, String field, Object value) {
		try {
			Field f = target.getClass().getDeclaredField(field);
			f.setAccessible(true);
			f.set(target, value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private static Object get(Object target, String field) {
		try {
			Field f = target.getClass().getDeclaredField(field);
			f.setAccessible(true);
			return f.get(target);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	@Test
	@DisplayName("Mail queue/exchange/binding ve DLQ topolojisi doÄŸru kuruluyor")
	void topology_ok() {
		MailQueueConfig cfg = new MailQueueConfig();
		
		set(cfg, "mailQueueName", "mail.queue");
		set(cfg, "mailExchangeName", "mail.exchange");
		set(cfg, "mailRoutingKey", "mail.send");
		set(cfg, "mailDlqName", "mail.queue.dlq");
		set(cfg, "mailDlxExchangeName", "mail.dlx");
		set(cfg, "mailMessageTtlMs", 120_000L);
		
		Queue q = cfg.mailQueue();
		assertThat(q.isDurable()).isTrue();
		assertThat(q.getName()).isEqualTo("mail.queue");
		Map<String,Object> args = q.getArguments();
		assertThat(args).containsEntry("x-dead-letter-exchange", "mail.dlx");
		assertThat(args).containsEntry("x-dead-letter-routing-key", "mail.queue.dlq");
		// TTL tip gÃ¼venli kontrol
		assertThat(args).containsKey("x-message-ttl");
		assertThat(((Number) args.get("x-message-ttl")).longValue()).isEqualTo(120_000L);
		
		DirectExchange ex = cfg.mailExchange();
		assertThat(ex.getName()).isEqualTo("mail.exchange");
		assertThat(ex.isDurable()).isTrue();
		assertThat(ex.isAutoDelete()).isFalse();
		
		Binding b = cfg.mailBinding(q, ex);
		assertThat(b.getExchange()).isEqualTo("mail.exchange");
		assertThat(b.getRoutingKey()).isEqualTo("mail.send");
		assertThat(b.getDestination()).isEqualTo("mail.queue");
		assertThat(b.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
		
		Queue dlq = cfg.mailDlqQueue();
		assertThat(dlq.isDurable()).isTrue();
		assertThat(dlq.getName()).isEqualTo("mail.queue.dlq");
		
		DirectExchange dlx = cfg.mailDlqExchange();
		assertThat(dlx.getName()).isEqualTo("mail.dlx");
		assertThat(dlx.isDurable()).isTrue();
		
		Binding dlqBind = cfg.mailDlqBinding(dlq, dlx);
		assertThat(dlqBind.getExchange()).isEqualTo("mail.dlx");
		assertThat(dlqBind.getRoutingKey()).isEqualTo("mail.queue.dlq");
		assertThat(dlqBind.getDestination()).isEqualTo("mail.queue.dlq");
		assertThat(dlqBind.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
	}
	
    @Test
    void boundedQuorumRetryQueuesRetainExpiredMessagesUntilTargetConfirms() {
        MailQueueConfig cfg = new MailQueueConfig();
        set(cfg, "mailQueueName", "mail.queue");
        set(cfg, "mailExchangeName", "mail.exchange");
        set(cfg, "mailRoutingKey", "mail.send");
        var queues = cfg.mailRetryQueues().getDeclarablesByType(Queue.class);
        assertThat(queues).hasSize(11);
        assertThat(queues).allSatisfy(queue -> {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum")
                    .containsEntry("x-dead-letter-strategy", "at-least-once")
                    .containsEntry("x-overflow", "reject-publish")
                    .containsEntry("x-dead-letter-exchange", "mail.exchange")
                    .containsEntry("x-dead-letter-routing-key", "mail.send")
                    .containsEntry("x-max-length", 1000)
                    .containsEntry("x-max-length-bytes", 4194304L);
        });
        assertThat(queues.stream().map(Queue::getName)).doesNotHaveDuplicates();
        assertThat(MailRetryTopology.bucketDelayMs(3_000)).isEqualTo(3_000);
        assertThat(MailRetryTopology.bucketDelayMs(10_001)).isEqualTo(30_000);
        assertThat(MailRetryTopology.bucketDelayMs(7_200_001)).isEqualTo(21_600_000);
        assertThat(MailRetryTopology.bucketDelayMs(86_400_000)).isEqualTo(86_400_000);
        for (long invalid : new long[] {-1, 0, 86_400_001}) {
            assertThatThrownBy(() -> MailRetryTopology.bucketDelayMs(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void freshQuorumMainAndDlqKeepApplicationRetryLimitAndSafeTerminalTransfer() {
        MailQueueConfig cfg = new MailQueueConfig();
        set(cfg, "mailQueueName", "mail.staging.v2.queue");
        set(cfg, "mailQueueType", "quorum");
        set(cfg, "mailDlxExchangeName", "mail.staging.v2.dlx");
        set(cfg, "mailDlqName", "mail.staging.v2.dlq");
        set(cfg, "mailMessageTtlMs", 120_000L);
        cfg.validateQueueType();
        assertThat(cfg.mailQueue().getArguments()).containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-strategy", "at-least-once")
                .containsEntry("x-overflow", "reject-publish")
                .containsEntry("x-delivery-limit", -1).containsEntry("x-message-ttl", 120_000L);
        assertThat(cfg.mailDlqQueue().getArguments()).containsEntry("x-queue-type", "quorum")
                .containsEntry("x-overflow", "reject-publish").doesNotContainKey("x-message-ttl");
        set(cfg, "mailQueueType", "other");
        assertThatThrownBy(cfg::validateQueueType).isInstanceOf(IllegalStateException.class);
    }

	@Test
	@DisplayName("Listener factory: MANUAL ack, prefetch & concurrency 'min-max' parse ediliyor (reflection ile)")
	void listener_factory_ok() {
		MailQueueConfig cfg = new MailQueueConfig();
		
		set(cfg, "listenerPrefetch", 10);
		set(cfg, "listenerConcurrency", "2-7");
		
		CachingConnectionFactory ccf = new CachingConnectionFactory("localhost"); // sadece konfig iÃ§in
		Jackson2JsonMessageConverter conv = new Jackson2JsonMessageConverter();
		
		SimpleRabbitListenerContainerFactory f = cfg.rabbitListenerContainerFactory(ccf, conv);
		
		// Alanlar Ã¼st sÄ±nÄ±fta olabilir; deepGet ile oku
		Object ack = deepGet(f, "acknowledgeMode");          // AcknowledgeMode
		Object prefetch = deepGet(f, "prefetchCount");       // Integer
		Object conc = deepGet(f, "concurrentConsumers");     // Integer
		Object maxConc = deepGet(f, "maxConcurrentConsumers");// Integer
		Object cf = deepGet(f, "connectionFactory");         // ConnectionFactory
		Object mc = deepGet(f, "messageConverter");          // MessageConverter
		
		assertThat(ack).isEqualTo(AcknowledgeMode.MANUAL);
		assertThat(prefetch).isEqualTo(10);
		assertThat(conc).isEqualTo(2);
		assertThat(maxConc).isEqualTo(7);
		assertThat(cf).isSameAs(ccf);
		assertThat(mc).isSameAs(conv);
	}
	
	
	// YardÄ±mcÄ±: herhangi bir no-arg method bul (MethodRabbitListenerEndpoint istiyor)
	private static java.lang.reflect.Method findAnyNoArgMethod(Class<?> clazz) {
		try {
			return Object.class.getDeclaredMethod("toString");
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}
	// --- yardÄ±mcÄ±lar (sÄ±nÄ±fÄ±n en Ã¼stÃ¼ne ekle) ---
	private static Object deepGet(Object target, String field) {
		Class<?> c = target.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(field);
				f.setAccessible(true);
				return f.get(target);
			} catch (NoSuchFieldException ignored) {
				c = c.getSuperclass();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		throw new RuntimeException(new NoSuchFieldException(field));
	}
	
	private static void deepSet(Object target, String field, Object value) {
		Class<?> c = target.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(field);
				f.setAccessible(true);
				f.set(target, value);
				return;
			} catch (NoSuchFieldException ignored) {
				c = c.getSuperclass();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		throw new RuntimeException(new NoSuchFieldException(field));
	}
}
