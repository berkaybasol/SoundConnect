package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.event.DmModeratedEvent;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/** Authorization belongs to the MANAGE_DM controller; all content changes share one commit. */
@Service
@RequiredArgsConstructor
public class DmModerationService {
    private final DMConversationRepository conversations;
    private final DMMessageRepository messages;
    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;

    @Transactional
    public void deleteConversation(UUID conversationId) {
        DMConversation conversation = lock(conversationId);
        // The parent lock fences sends, ACKs and late notification admission.
        // Receipt tombstones are deliberately retained. Push jobs cascade from
        // the removed inbox rows through their existing foreign key.
        notifications.deleteDmConversationNotifications(conversationId.toString());
        messages.deleteAllByConversationId(conversationId);
        conversations.delete(conversation);
        events.publishEvent(new DmModeratedEvent(Set.of(conversation.getUserAId(), conversation.getUserBId())));
    }

    @Transactional
    public void deleteMessage(UUID conversationId, UUID messageId) {
        DMConversation conversation = lock(conversationId);
        var message = messages.findByIdForUpdate(messageId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.MESSAGE_NOT_FOUND));
        if (!conversationId.equals(message.getConversationId()))
            throw new SoundConnectException(ErrorType.MESSAGE_NOT_FOUND);
        notifications.deleteDmMessageNotifications(messageId.toString());
        messages.delete(message);
        messages.flush();
        var last = messages.findTopByConversationIdOrderByCreatedAtDesc(conversationId).orElse(null);
        conversation.setLastMessageAt(last == null ? null : last.getCreatedAt());
        if (messageId.equals(conversation.getLastReadMessageId())) conversation.setLastReadMessageId(null);
        conversations.save(conversation);
        events.publishEvent(new DmModeratedEvent(Set.of(conversation.getUserAId(), conversation.getUserBId())));
    }

    private DMConversation lock(UUID conversationId) {
        return conversations.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.CONVERSATION_NOT_FOUND));
    }
}
