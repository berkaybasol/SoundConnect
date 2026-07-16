package com.berkayb.soundconnect.modules.media.transcode;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MediaTranscodeWorkerConditionalTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(MediaRabbitListenerConfiguration.class)
			.withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class));

	@Test
	void disabledWorkerDoesNotCreateVideoListenerFactory() {
		contextRunner
				.withPropertyValues("media.transcode.worker.enabled=false")
				.run(context -> assertThat(context)
						.doesNotHaveBean("mediaVideoHlsListenerFactory"));
	}

	@Test
	void localWorkerCreatesBoundedVideoListenerFactory() {
		contextRunner
				.withPropertyValues("media.transcode.worker.enabled=true")
				.run(context -> assertThat(context)
						.hasBean("mediaVideoHlsListenerFactory"));
	}

	@Test
	void listenerAndFactoryShareTheSameFailClosedSwitch() {
		ConditionalOnProperty listenerCondition = AnnotatedElementUtils.findMergedAnnotation(
				VideoHlsListener.class, ConditionalOnProperty.class);
		ConditionalOnProperty factoryCondition = AnnotatedElementUtils.findMergedAnnotation(
				MediaRabbitListenerConfiguration.class, ConditionalOnProperty.class);

		assertThat(listenerCondition).isNotNull();
		assertThat(factoryCondition).isNotNull();
		assertThat(listenerCondition.prefix()).isEqualTo("media.transcode.worker");
		assertThat(factoryCondition.prefix()).isEqualTo("media.transcode.worker");
		assertThat(listenerCondition.name()).containsExactly("enabled");
		assertThat(factoryCondition.name()).containsExactly("enabled");
		assertThat(listenerCondition.matchIfMissing()).isFalse();
		assertThat(factoryCondition.matchIfMissing()).isFalse();
	}
}
