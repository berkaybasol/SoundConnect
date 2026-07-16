package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class VideoHlsListenerTest {

	@Mock VideoHlsWorkflow workflow;
	@Mock MediaHlsWorkExecutor workExecutor;
	@Mock Channel channel;
	ObjectMapper mapper;
	VideoHlsListener listener;

	@BeforeEach
	void setup() {
		mapper = new ObjectMapper();
		listener = new VideoHlsListener(workflow, mapper, workExecutor);
	}

	@Test
	void claimIsAckedAndHandedOffWithoutLongWorkOnListenerThread() throws Exception {
		UUID id = UUID.randomUUID();
		Message message = message(id);
		var work = new VideoHlsWorkflow.ClaimedVideoHlsWork(
				id, UUID.randomUUID(), "verified/media/source.mp4", "media/hls", 1);
		when(workExecutor.tryReserve()).thenReturn(true);
		when(workflow.claim(any())).thenReturn(Optional.of(work));
		when(workExecutor.submitReserved(work)).thenReturn(true);

		listener.onMessage(message, channel);

		InOrder order = inOrder(workExecutor, workflow, channel);
		order.verify(workExecutor).tryReserve();
		order.verify(workflow).claim(any(VideoHlsRequest.class));
		order.verify(workExecutor).submitReserved(work);
		order.verify(channel).basicAck(42L, false);
		verify(workflow, never()).processClaimed(any());
		verify(workExecutor, never()).releaseReservation();
	}

	@Test
	void rejectedHandoffReturnsExactClaimBeforeAcknowledging() throws Exception {
		UUID id = UUID.randomUUID();
		var work = new VideoHlsWorkflow.ClaimedVideoHlsWork(
				id, UUID.randomUUID(), "verified/media/source.mp4", "media/hls", 1);
		when(workExecutor.tryReserve()).thenReturn(true);
		when(workflow.claim(any())).thenReturn(Optional.of(work));
		when(workExecutor.submitReserved(work)).thenReturn(false);
		when(workflow.abandonClaimForRetry(work)).thenReturn(true);

		listener.onMessage(message(id), channel);

		InOrder order = inOrder(workExecutor, workflow, channel);
		order.verify(workExecutor).submitReserved(work);
		order.verify(workflow).abandonClaimForRetry(work);
		order.verify(channel).basicAck(42L, false);
		verify(workExecutor, never()).releaseReservation();
	}

	@Test
	void throwingHandoffReturnsExactClaimBeforeAcknowledging() throws Exception {
		UUID id = UUID.randomUUID();
		var work = new VideoHlsWorkflow.ClaimedVideoHlsWork(
				id, UUID.randomUUID(), "verified/media/source.mp4", "media/hls", 1);
		when(workExecutor.tryReserve()).thenReturn(true);
		when(workflow.claim(any())).thenReturn(Optional.of(work));
		when(workExecutor.submitReserved(work)).thenThrow(new IllegalStateException("shutdown"));
		when(workflow.abandonClaimForRetry(work)).thenReturn(true);

		listener.onMessage(message(id), channel);

		InOrder order = inOrder(workExecutor, workflow, channel);
		order.verify(workExecutor).submitReserved(work);
		order.verify(workflow).abandonClaimForRetry(work);
		order.verify(channel).basicAck(42L, false);
		verify(workExecutor, never()).releaseReservation();
	}

	@Test
	void ackFailureAfterSuccessfulHandoffNeverAbandonsLiveWorker() throws Exception {
		UUID id = UUID.randomUUID();
		var work = new VideoHlsWorkflow.ClaimedVideoHlsWork(
				id, UUID.randomUUID(), "verified/media/source.mp4", "media/hls", 1);
		when(workExecutor.tryReserve()).thenReturn(true);
		when(workflow.claim(any())).thenReturn(Optional.of(work));
		when(workExecutor.submitReserved(work)).thenReturn(true);
		doThrow(new IOException("channel closed")).when(channel).basicAck(42L, false);

		assertThatThrownBy(() -> listener.onMessage(message(id), channel))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("post-claim");

		verify(workExecutor).submitReserved(work);
		verify(workflow, never()).abandonClaimForRetry(any());
		verify(workExecutor, never()).releaseReservation();
	}

	@Test
	void saturatedWorkerDefersSentIntentAndAcknowledgesSignal() throws Exception {
		UUID id = UUID.randomUUID();
		when(workExecutor.tryReserve()).thenReturn(false);
		when(workflow.deferSignal(any())).thenReturn(true);

		listener.onMessage(message(id), channel);

		verify(workflow).deferSignal(any(VideoHlsRequest.class));
		verify(channel).basicAck(42L, false);
		verify(workflow, never()).claim(any());
		verify(workExecutor, never()).submitReserved(any());
	}

	@Test
	void duplicateClaimAcknowledgesAndReleasesAdmissionPermit() throws Exception {
		UUID id = UUID.randomUUID();
		when(workExecutor.tryReserve()).thenReturn(true);
		when(workflow.claim(any())).thenReturn(Optional.empty());

		listener.onMessage(message(id), channel);

		verify(channel).basicAck(42L, false);
		verify(workExecutor).releaseReservation();
		verify(workExecutor, never()).submitReserved(any());
	}

	@Test
	void claimFailureReleasesPermitAndRejectsWithoutRequeue() {
		UUID id = UUID.randomUUID();
		when(workExecutor.tryReserve()).thenReturn(true);
		when(workflow.claim(any())).thenThrow(new IllegalStateException("database unavailable"));

		assertThatThrownBy(() -> listener.onMessage(message(id), channel))
				.isInstanceOf(AmqpRejectAndDontRequeueException.class)
				.hasMessageContaining("claim failed");

		verify(workExecutor).releaseReservation();
		verifyNoInteractions(channel);
	}

	@Test
	void malformedJsonIsRejectedBeforeAdmissionOrAck() {
		Message message = rawMessage("{not-json}");

		assertThatThrownBy(() -> listener.onMessage(message, channel))
				.isInstanceOf(AmqpRejectAndDontRequeueException.class);

		verifyNoInteractions(workflow, workExecutor, channel);
	}

	private Message message(UUID id) throws Exception {
		return rawMessage(mapper.writeValueAsString(new VideoHlsRequest(
				id.toString(), "media/source.mp4", "media/hls", "2025-09-01T12:00:00Z", 1)));
	}

	private static Message rawMessage(String json) {
		MessageProperties properties = new MessageProperties();
		properties.setReceivedRoutingKey("video.hls.request");
		properties.setCorrelationId("corr");
		properties.setDeliveryTag(42L);
		return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
	}
}
