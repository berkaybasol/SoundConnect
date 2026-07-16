package com.berkayb.soundconnect.modules.media.transcode;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MediaRabbitListenerConfigurationTest {

	@Test
	void videoFactoryUsesSingleFairPrefetchAndManualEarlyAck() {
		var factory = new MediaRabbitListenerConfiguration()
				.mediaVideoHlsListenerFactory(mock(ConnectionFactory.class), true);

		assertThat(ReflectionTestUtils.getField(factory, "prefetchCount")).isEqualTo(1);
		assertThat(ReflectionTestUtils.getField(factory, "acknowledgeMode"))
				.isEqualTo(AcknowledgeMode.MANUAL);
		assertThat(ReflectionTestUtils.getField(factory, "defaultRequeueRejected"))
				.isEqualTo(false);
		assertThat(ReflectionTestUtils.getField(factory, "autoStartup")).isEqualTo(true);
	}
}
