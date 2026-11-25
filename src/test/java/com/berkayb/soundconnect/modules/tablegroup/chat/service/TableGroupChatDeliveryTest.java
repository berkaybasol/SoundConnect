package com.berkayb.soundconnect.modules.tablegroup.chat.service;

import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TableGroupChatDeliveryTest {
    @Mock TableGroupRepository tables;
    @Mock TableGroupMessageRepository messages;
    @Mock TableGroupMessageMapper mapper;
    @Mock UserBlockService blocks;
    @Mock TableGroupChatUnreadHelper unread;
    @Mock SimpMessagingTemplate broker;
    @Mock TableGroupMetrics metrics;
    @InjectMocks TableGroupChatDelivery delivery;

    @Test void lateDeliverySkipsBlockedPeerAndRetainsThirdPartyDespiteCacheOrBrokerFailure() {
        UUID tableId = UUID.randomUUID(), id = UUID.randomUUID(), sender = UUID.randomUUID();
        UUID hidden = UUID.randomUUID(), third = UUID.randomUUID(), departed = UUID.randomUUID();
        var table = TableGroup.builder().status(TableGroupStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(600)).participants(Set.of(member(sender), member(hidden), member(third))).build();
        var row = TableGroupMessage.builder().id(id).senderId(sender).tableGroupId(tableId).messageType(MessageType.TEXT)
                .content("merhaba").build();
        var dto = new TableGroupMessageResponseDto(id, tableId, sender, "merhaba", MessageType.TEXT, Instant.now(), null);
        when(tables.findById(tableId)).thenReturn(Optional.of(table));
        when(messages.findById(id)).thenReturn(Optional.of(row));
        when(mapper.toResponseDto(row)).thenReturn(dto);
        when(blocks.blockedAmong(sender, Set.of(sender, hidden, third))).thenReturn(Set.of(hidden));
        doThrow(new IllegalStateException("Redis")).when(unread).incrementUnread(third, tableId);
        doThrow(new IllegalStateException("broker")).when(broker).convertAndSend(WebSocketChannels.tableGroup(tableId, sender), dto);
        doNothing().when(broker).convertAndSend(WebSocketChannels.tableGroup(tableId, third), dto);

        delivery.publish(tableId, id, Set.of(sender, hidden, third, departed));

        verify(unread).incrementUnread(third, tableId);
        verifyNoMoreInteractions(unread);
        verify(broker).convertAndSend(WebSocketChannels.tableGroup(tableId, third), dto);
        verify(broker).convertAndSend(WebSocketChannels.tableGroup(tableId, sender), dto);
        verifyNoMoreInteractions(broker);
        verify(metrics).unreadCacheFailed("delivery");
        verify(metrics).realtimePublishFailed();
    }

    private TableGroupParticipant member(UUID id) {
        return TableGroupParticipant.builder().userId(id).status(ParticipantStatus.ACCEPTED).build();
    }
}
