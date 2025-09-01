// src/test/java/com/berkayb/soundconnect/modules/media/transcode/TranscodePublisherImplTest.java
package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class TranscodePublisherImplTest {
	
	@Mock RabbitTemplate rabbitTemplate;
	@Captor ArgumentCaptor<VideoHlsRequest> payloadCaptor;
	@Captor ArgumentCaptor<MessagePostProcessor> mppCaptor;
	@Captor ArgumentCaptor<CorrelationData> cdCaptor;
	
	TranscodePublisherImpl publisher;
	
	@BeforeEach
	void setup() {
		publisher = new TranscodePublisherImpl(rabbitTemplate);
		// @Value alanlarını set et
		ReflectionTestUtils.setField(publisher, "exchange", "ex.media");
		ReflectionTestUtils.setField(publisher, "videoHlsRoutingKey", "media.video.hls");
		// @PostConstruct init()
		publisher.init();
	}
	
	@Test
	void init_should_wire_mandatory_and_callbacks() {
		// init çağrılmış olmalı
		verify(rabbitTemplate, times(1)).setMandatory(true);
		verify(rabbitTemplate, times(1)).setConfirmCallback(any());
		verify(rabbitTemplate, times(1)).setReturnsCallback(any());
	}
	
	@Test
	void publishVideoHls_happyPath_sends_withHeaders_andCorrelationId() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "media/" + assetId + "/source.mp4";
		String hlsPrefix = "media/" + assetId + "/hls";
		
		publisher.publishVideoHls(assetId, sourceKey, hlsPrefix);
		
		// convertAndSend(exchange, routingKey, payload, mpp, correlationData)
		verify(rabbitTemplate).convertAndSend(
				eq("ex.media"),
				eq("media.video.hls"),
				payloadCaptor.capture(),
				mppCaptor.capture(),
				cdCaptor.capture()
		);
		
		// payload alanları
		VideoHlsRequest req = payloadCaptor.getValue();
		assertThat(req.assetId()).isEqualTo(assetId.toString());
		assertThat(req.sourceKey()).isEqualTo(sourceKey);
		assertThat(req.hlsPrefix()).isEqualTo(hlsPrefix);
		assertThat(req.schemaVersion()).isEqualTo(1);
		assertThat(req.requestedAtIso()).isNotBlank();
		
		// correlation id = assetId
		assertThat(cdCaptor.getValue().getId()).isEqualTo(assetId.toString());
		
		// header’ları doğrulamak için MessagePostProcessor’ı gerçek bir Message üzerinde çalıştır
		MessageProperties mp = new MessageProperties();
		Message msg = new Message("x".getBytes(StandardCharsets.UTF_8), mp);
		Message processed = mppCaptor.getValue().postProcessMessage(msg);
		
		assertThat(processed.getMessageProperties().getDeliveryMode()).isNotNull(); // PERSISTENT
		assertThat(processed.getMessageProperties().getHeaders())
				.containsEntry("eventType", "VIDEO_HLS_REQUEST")
				.containsEntry("assetId", assetId.toString())
				.containsEntry("schemaVersion", 1);
	}
	
	@Test
	void publishVideoHls_nullOrBlankParams_should_throwSoundConnectException() {
		// init() sırasında yapılan setMandatory / setConfirmCallback / setReturnsCallback
		// etkileşimlerini test geçmişinden düş.
		org.mockito.Mockito.clearInvocations(rabbitTemplate);
		
		UUID assetId = UUID.randomUUID();
		
		assertThatThrownBy(() -> publisher.publishVideoHls(null, "k", "p"))
				.isInstanceOf(SoundConnectException.class);
		assertThatThrownBy(() -> publisher.publishVideoHls(assetId, "", "p"))
				.isInstanceOf(SoundConnectException.class);
		assertThatThrownBy(() -> publisher.publishVideoHls(assetId, "k", ""))
				.isInstanceOf(SoundConnectException.class);
		
		// Bu testte method daha başta parametre hatasından fırlattığı için
		// RabbitTemplate ile yeni bir etkileşim olmamalı.
		verifyNoInteractions(rabbitTemplate);
	}
	
	@Test
	void publishVideoHls_when_rabbitTemplateThrows_wraps_into_SoundConnectException() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "media/" + assetId + "/source.mp4";
		String hlsPrefix = "media/" + assetId + "/hls";
		
		doThrow(new RuntimeException("broker down")).when(rabbitTemplate)
		                                            .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
		
		assertThatThrownBy(() -> publisher.publishVideoHls(assetId, sourceKey, hlsPrefix))
				.isInstanceOf(SoundConnectException.class);
	}
}