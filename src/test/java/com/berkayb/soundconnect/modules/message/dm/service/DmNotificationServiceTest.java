package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.event.DmMessageSentEvent;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class DmNotificationServiceTest {
    final TransactionalNotificationService inbox = mock(TransactionalNotificationService.class);
    final UserRepository users = mock(UserRepository.class);
    final PublicProfileResolverService profiles = mock(PublicProfileResolverService.class);
    final DmNotificationService service = new DmNotificationService(inbox, users, profiles);
    final UUID sender = UUID.randomUUID(), recipient = UUID.randomUUID(), conversation = UUID.randomUUID(),
            message = UUID.randomUUID();

    @Test
    void deterministicEventIdentityPreservesPayloadAndBoundedPreview() {
        profile("Basol", "https://example.test/avatar", null);
        var event = event("x".repeat(180));
        service.persist(event);
        service.persist(event);
        var captor = ArgumentCaptor.forClass(NotificationInboundEvent.class);
        verify(inbox, times(2)).persistInCurrentTransaction(captor.capture());
        var first = captor.getAllValues().getFirst();
        assertThat(first.eventId()).isEqualTo(captor.getAllValues().getLast().eventId())
                .isEqualTo(DmNotificationService.eventId(message, recipient));
        assertThat(first.type()).isEqualTo(NotificationType.DM_NEW_MESSAGE);
        assertThat(first.title()).isEqualTo("Basol size bir mesaj gönderdi");
        assertThat(first.message()).isEqualTo("x".repeat(120) + "...");
        assertThat(first.emailForce()).isFalse();
        assertThat(first.payload()).containsEntry("module", "DM")
                .containsEntry("conversationId", conversation.toString()).containsEntry("messageId", message.toString())
                .containsEntry("senderId", sender.toString()).containsEntry("recipientId", recipient.toString())
                .containsEntry("senderUsername", "Basol").containsEntry("senderAvatarUrl", "https://example.test/avatar");
        assertThat(DmNotificationService.eventId(message, UUID.randomUUID())).isNotEqualTo(first.eventId());
    }

    @Test
    void longDisplayNameAndEmojiPreviewStayWithinStorageBoundsWithoutBreakingSurrogatePairs() {
        profile("🎵".repeat(150), "https://example.test/avatar", null);
        service.persist(event("x".repeat(119) + "🎵" + "remaining text"));
        var notification = captured();
        assertThat(notification.title().length()).isLessThanOrEqualTo(160);
        assertThat(notification.title()).endsWith(" size bir mesaj gönderdi");
        assertThat(notification.message()).isEqualTo("x".repeat(119) + "...");
        assertThat(notification.title()).doesNotContain("\uFFFD");
    }

    @Test
    void ghostSnapshotUsesCanonicalAliasAndNeverFallsBackToRealAvatar() {
        profile("ghosthandle", null, ListenerVisibilityMode.GHOST);
        service.persist(event("hello"));
        var notification = captured();
        assertThat(notification.payload()).containsEntry("senderUsername", "ghosthandle")
                .containsEntry("senderAvatarUrl", "").containsEntry("senderVisibilityMode", "GHOST");
        verifyNoInteractions(users);
    }

    @Test
    void failedIdentityLookupUsesSanitizedIdentity() {
        when(profiles.resolveByUserId(sender)).thenThrow(new IllegalStateException("identity unavailable"));
        service.persist(event("hello"));
        var notification = captured();
        assertThat(notification.title()).isEqualTo("Bir kullanici size bir mesaj gönderdi");
        assertThat(notification.payload()).containsEntry("senderUsername", "Bir kullanici")
                .containsEntry("senderAvatarUrl", "");
        verifyNoInteractions(users);
    }

    @Test
    void inboxFailurePropagatesSoTheMessageTransactionCannotCommitAlone() {
        profile("Basol", "https://example.test/avatar", null);
        doThrow(new IllegalStateException("database write failed")).when(inbox).persistInCurrentTransaction(any());
        assertThatThrownBy(() -> service.persist(event("hello"))).isInstanceOf(IllegalStateException.class)
                .hasMessage("database write failed");
    }

    private NotificationInboundEvent captured() {
        var captor = ArgumentCaptor.forClass(NotificationInboundEvent.class);
        verify(inbox).persistInCurrentTransaction(captor.capture());
        return captor.getValue();
    }

    private void profile(String name, String avatar, ListenerVisibilityMode visibility) {
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,
                List.of(new UserProfileTargetDto("LISTENER", UUID.randomUUID(), name, avatar, visibility))));
    }

    private DmMessageSentEvent event(String text) {
        return DmMessageSentEvent.builder().messageId(message).conversationId(conversation)
                .senderId(sender).recipientId(recipient).messageType("text").content(text).build();
    }
}
