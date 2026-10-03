package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class NotificationLookupServiceTest {
    final NotificationRepository repository = mock(NotificationRepository.class);
    final NotificationMapper mapper = mock(NotificationMapper.class);
    final NotificationBadgeCacheHelper badges = mock(NotificationBadgeCacheHelper.class);
    final NotificationWebSocketService websocket = mock(NotificationWebSocketService.class);
    final GhostListenerIdentityBatchResolver identities = mock(GhostListenerIdentityBatchResolver.class);
    final NotificationReceiptRepository receipts = mock(NotificationReceiptRepository.class);
    final NotificationServiceImpl service = new NotificationServiceImpl(
            repository, mapper, badges, websocket, identities, receipts);
    final UUID owner = UUID.randomUUID(), id = UUID.randomUUID();

    @Test void unreadLookupDoesNotAcknowledgeTheRowOrTouchOtherBadges() {
        var entity = Notification.builder().recipientId(owner)
                .type(NotificationType.ARTIST_VENUE_LINK_APPLICATION_REQUEST).read(false).build();
        var dto = visible(entity, Map.of("module", "ARTIST_VENUE", "requestId", UUID.randomUUID().toString()));

        assertThat(service.getUserNotification(owner, id)).isEqualTo(dto);
        assertThat(entity.isRead()).isFalse();
        verify(repository).findByIdAndRecipientId(id, owner);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(badges, websocket, receipts, identities);
    }

    @Test void alreadyReadOwnedNotificationCanStillResolveItsDestination() {
        var entity = Notification.builder().recipientId(owner)
                .type(NotificationType.EVENT_PERFORMER_APPROVED).read(true).build();
        var dto = visible(entity, Map.of("module", "EVENT_PERFORMER"));
        assertThat(service.getUserNotification(owner, id)).isEqualTo(dto);
        assertThat(entity.isRead()).isTrue();
        verify(repository).findByIdAndRecipientId(id, owner);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(badges, websocket, receipts, identities);
    }

    @Test void absentAndNonVisibleIdsUseTheSameNonDisclosingError() {
        UUID foreign = UUID.randomUUID();
        when(repository.findByIdAndRecipientId(id, owner)).thenReturn(Optional.empty());
        when(repository.findByIdAndRecipientId(foreign, owner)).thenReturn(Optional.empty());
        for (UUID target : List.of(id, foreign)) {
            assertThatThrownBy(() -> service.getUserNotification(owner, target))
                    .isInstanceOfSatisfying(SoundConnectException.class,
                            error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.NOTIFICATION_NOT_FOUND));
            verify(repository).findByIdAndRecipientId(target, owner);
        }
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(mapper, badges, websocket, receipts, identities);
    }

    @Test void singleLookupRefreshesOldGhostIdentityInsteadOfExposingItsStoredSnapshot() {
        UUID actor = UUID.randomUUID();
        var entity = Notification.builder().recipientId(owner).type(NotificationType.DM_NEW_MESSAGE).read(false).build();
        var stale = visible(entity, Map.of("senderId", actor.toString(), "senderUsername", "old-private-name",
                "senderAvatarUrl", "https://fixture.invalid/old-face"));
        when(identities.resolve(anyCollection())).thenReturn(Map.of(actor,
                new GhostListenerIdentity(actor, "current_alias", null, ListenerVisibilityMode.GHOST)));

        var result = service.getUserNotification(owner, id);
        assertThat(result.payload()).containsEntry("senderUsername", "current_alias")
                .containsEntry("senderVisibilityMode", "GHOST").doesNotContainKey("senderAvatarUrl");
        assertThat(result.title()).doesNotContain("old-private-name");
        assertThat(stale.payload()).containsEntry("senderUsername", "old-private-name");
        assertThat(result.read()).isFalse();
        verify(repository).findByIdAndRecipientId(id, owner);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(badges, websocket, receipts);
    }

    @Test void identityRefreshFailureRemovesPrivateSnapshotWithoutAcknowledging() {
        UUID actor = UUID.randomUUID();
        var entity = Notification.builder().recipientId(owner).type(NotificationType.DM_NEW_MESSAGE).read(false).build();
        visible(entity, Map.of("senderId", actor.toString(), "senderUsername", "old-private-name",
                "senderAvatarUrl", "https://fixture.invalid/old-face"));
        when(identities.resolve(anyCollection())).thenThrow(new DataAccessResourceFailureException("fixture"));
        var result = service.getUserNotification(owner, id);
        assertThat(result.payload()).containsEntry("senderId", actor.toString())
                .doesNotContainKeys("senderUsername", "senderAvatarUrl");
        assertThat(result.read()).isFalse();
        verifyNoInteractions(badges, websocket, receipts);
    }

    @Test void databaseFailureIsNotMisreportedAsMissingOrSuccessfulRead() {
        when(repository.findByIdAndRecipientId(id, owner)).thenThrow(new DataAccessResourceFailureException("fixture"));
        assertThatThrownBy(() -> service.getUserNotification(owner, id))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(mapper, badges, websocket, receipts, identities);
    }

    @Test void nullBoundaryArgumentsAreRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.getUserNotification(null, id)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.getUserNotification(owner, null)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(repository, mapper, badges, websocket, receipts, identities);
    }

    private NotificationResponseDto visible(Notification entity, Map<String, Object> payload) {
        var dto = new NotificationResponseDto(id, owner, entity.getType(), "fixture title", "fixture body",
                entity.isRead(), Instant.parse("2026-09-24T00:00:00Z"), payload);
        when(repository.findByIdAndRecipientId(id, owner)).thenReturn(Optional.of(entity));
        when(mapper.toDto(entity)).thenReturn(dto);
        return dto;
    }
}
