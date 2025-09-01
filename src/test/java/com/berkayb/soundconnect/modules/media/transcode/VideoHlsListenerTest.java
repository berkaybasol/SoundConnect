// src/test/java/com/berkayb/soundconnect/modules/media/transcode/VideoHlsListenerTest.java
package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class VideoHlsListenerTest {
	
	@Mock
	VideoHlsWorkflow workflow;
	
	ObjectMapper mapper;
	
	VideoHlsListener listener;
	
	@BeforeEach
	void setup() {
		mapper = new ObjectMapper();
		listener = new VideoHlsListener(workflow, mapper);
	}
	
	private static Message messageWithBody(String json, String rk, String corrId) {
		MessageProperties props = new MessageProperties();
		props.setReceivedRoutingKey(rk);
		props.setCorrelationId(corrId);
		return new Message(json.getBytes(StandardCharsets.UTF_8), props);
	}
	
	@Test
	void onMessage_happyPath_callsWorkflow_and_ack() throws Exception {
		UUID id = UUID.randomUUID();
		VideoHlsRequest dto = new VideoHlsRequest(
				id.toString(),
				"media/" + id + "/source.mp4",
				"media/" + id + "/hls",
				"2025-09-01T12:00:00Z",
				1
		);
		String json = mapper.writeValueAsString(dto);
		Message msg = messageWithBody(json, "media.video.hls", id.toString());
		
		// act
		listener.onMessage(msg);
		
		// assert
		ArgumentCaptor<VideoHlsRequest> cap = ArgumentCaptor.forClass(VideoHlsRequest.class);
		verify(workflow, times(1)).process(cap.capture());
		assertThat(cap.getValue().assetId()).isEqualTo(id.toString());
		assertThat(cap.getValue().hlsPrefix()).isEqualTo("media/" + id + "/hls");
	}
	
	@Test
	void onMessage_whenWorkflowThrows_rejectsWithoutRequeue() throws Exception {
		UUID id = UUID.randomUUID();
		VideoHlsRequest dto = new VideoHlsRequest(
				id.toString(),
				"media/" + id + "/source.mp4",
				"media/" + id + "/hls",
				"2025-09-01T12:00:00Z",
				1
		);
		String json = mapper.writeValueAsString(dto);
		Message msg = messageWithBody(json, "media.video.hls", id.toString());
		
		doThrow(new RuntimeException("boom")).when(workflow).process(any());
		
		assertThatThrownBy(() -> listener.onMessage(msg))
				.isInstanceOf(AmqpRejectAndDontRequeueException.class)
				.hasMessageContaining("workflow failed");
		
		verify(workflow, times(1)).process(any());
	}
	
	@Test
	void onMessage_malformedJson_rejectsWithoutRequeue() {
		String badJson = "{ not-a-valid-json }";
		Message msg = messageWithBody(badJson, "media.video.hls", "corr-1");
		
		assertThatThrownBy(() -> listener.onMessage(msg))
				.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		
		verifyNoInteractions(workflow);
	}
}