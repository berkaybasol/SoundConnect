package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.abuse.DmRateLimitGuard;
import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageEventPublisher;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The zone-less DM wire contract represents UTC, independently of server locale. */
@Isolated("Temporarily changes the JVM default timezone, restored after each case")
class DmTimestampContractTest {
    private final DMMessageRepository messages = mock(DMMessageRepository.class);
    private final DMConversationRepository conversations = mock(DMConversationRepository.class);
    private final DmSendReceiptStore receipts = mock(DmSendReceiptStore.class);
    private final DMMessageServiceImpl service = new DMMessageServiceImpl(messages, conversations,
            mock(DMMessageMapper.class), mock(DmMessageEventPublisher.class), mock(NotificationService.class),
            mock(AccountDeliveryFence.class), mock(DmNotificationService.class), receipts, mock(DmRateLimitGuard.class));
    private final UUID sender = UUID.randomUUID(), recipient = UUID.randomUUID(), conversationId = UUID.randomUUID();
    private final DMConversation conversation = DMConversation.builder().id(conversationId)
            .userAId(sender).userBId(recipient).build();
    private TimeZone originalTimezone;

    @BeforeEach void useNonUtcServerTimezone() {
        originalTimezone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
        when(conversations.findByIdForUpdate(conversationId)).thenReturn(Optional.of(conversation));
    }

    @AfterEach void restoreServerTimezone() { TimeZone.setDefault(originalTimezone); }

    @Test void sendingStoresUtcConversationTimeOnNonUtcServer() {
        when(messages.saveAndFlush(any(DMMessage.class))).thenAnswer(invocation -> {
            DMMessage message = invocation.getArgument(0);
            message.setId(UUID.randomUUID());
            return message;
        });
        var before = Instant.now();
        service.sendMessage(new DMMessageRequestDto(conversationId, recipient, "timezone fixture", "text"), sender);
        assertUtcBetween(conversation.getLastMessageAt(), before, Instant.now());
    }

    @Test void readingStoresUtcReadTimeOnNonUtcServer() {
        var id = UUID.randomUUID();
        var message = DMMessage.builder().id(id).conversationId(conversationId)
                .senderId(sender).recipientId(recipient).build();
        when(messages.findConversationIdByMessageId(id)).thenReturn(Optional.of(conversationId));
        when(messages.findByIdForUpdate(id)).thenReturn(Optional.of(message));
        var before = Instant.now();
        service.markMessageAsRead(id, recipient);
        assertUtcBetween(message.getReadAt(), before, Instant.now());
    }

    private static void assertUtcBetween(LocalDateTime value, Instant before, Instant after) {
        assertThat(value).isNotNull();
        assertThat(value.toInstant(ZoneOffset.UTC)).isBetween(before, after);
    }
}
