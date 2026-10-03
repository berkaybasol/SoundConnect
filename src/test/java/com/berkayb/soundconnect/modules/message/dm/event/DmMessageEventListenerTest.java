package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("unit")
class DmMessageEventListenerTest {
    final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    final DMMessageMapper mapper = mock(DMMessageMapper.class);
    final DMMessageRepository messages = mock(DMMessageRepository.class);
    final AccountDeliveryFence accounts = com.berkayb.soundconnect.support.DeliveryPolicyTestSupport.activeAccounts();
    final DmMessageEventListener listener = new DmMessageEventListener(messaging, mapper, messages, accounts);
    final UUID conversationId = UUID.randomUUID(), senderId = UUID.randomUUID(), recipientId = UUID.randomUUID(),
            messageId = UUID.randomUUID();

    @Test
    void sentEventProjectsCommittedMessageToBothUsersAndDatabaseBadge() {
        var entity = DMMessage.builder().id(messageId).conversationId(conversationId)
                .senderId(senderId).recipientId(recipientId).content("hi").messageType("text").build();
        var dto = new DMMessageResponseDto(messageId, conversationId, senderId, recipientId, "hi", "text",
                LocalDateTime.now(), null, null);
        when(messages.findById(messageId)).thenReturn(Optional.of(entity));
        when(mapper.toResponseDto(entity)).thenReturn(dto);
        when(messages.countByRecipientIdAndReadAtIsNull(recipientId)).thenReturn(2L);
        listener.onDmMessageSent(event());
        verify(messaging).convertAndSend(WebSocketChannels.dm(recipientId), dto);
        verify(messaging).convertAndSend(WebSocketChannels.dm(senderId), dto);
        verify(messaging).convertAndSend(WebSocketChannels.dmBadge(recipientId), 2L);
        verifyNoMoreInteractions(messaging);
    }

    @Test
    void missingSourceNeverProjectsStaleEventContent() {
        when(messages.findById(messageId)).thenReturn(Optional.empty());
        listener.onDmMessageSent(event());
        verifyNoInteractions(mapper, messaging);
    }

    @Test
    void erasedParticipantStopsAllProjections() {
        when(accounts.canDeliver(any(), any())).thenReturn(false);
        listener.onDmMessageSent(event());
        verifyNoInteractions(messages, mapper, messaging);
    }

    @Test
    void readEventProjectsCurrentDatabaseBadge() {
        when(messages.countByRecipientIdAndReadAtIsNull(recipientId)).thenReturn(4L);
        listener.onDmMessageRead(new DmMessageReadEvent(conversationId, recipientId));
        verify(messaging).convertAndSend(WebSocketChannels.dmBadge(recipientId), 4L);
        verifyNoInteractions(mapper);
    }

    @Test
    void realtimeHandlersRunAfterCommitInFreshTransactions() throws Exception {
        var sent = DmMessageEventDispatcher.class.getMethod("onDmMessageSent", DmMessageSentEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        var read = DmMessageEventDispatcher.class.getMethod("onDmMessageRead", DmMessageReadEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        var transaction = DmMessageEventListener.class.getMethod("onDmMessageSent", DmMessageSentEvent.class)
                .getAnnotation(Transactional.class);
        assertThat(sent.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(read.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private DmMessageSentEvent event() {
        return DmMessageSentEvent.builder().messageId(messageId).conversationId(conversationId)
                .senderId(senderId).recipientId(recipientId).content("hi").messageType("text")
                .sentAt(LocalDateTime.now()).build();
    }
}