package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.*;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PushIngressHookTest {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void bothIngressPathsPublishInsideTransactionAndPlanningFailurePropagates(boolean rabbit) {
        var repository=mock(NotificationRepository.class);
        var receipts=mock(NotificationReceiptRepository.class);
        var policy=mock(NotificationDeliveryPolicy.class);
        var mapper=mock(NotificationMapper.class);
        var badges=mock(NotificationBadgeCacheHelper.class);
        var websocket=mock(NotificationWebSocketService.class);
        var service=mock(NotificationService.class);
        var publisher=mock(ApplicationEventPublisher.class);
        var event=NotificationInboundEvent.builder().eventId(UUID.randomUUID()).recipientId(UUID.randomUUID())
                .type(NotificationType.DM_NEW_MESSAGE).occurredAt(Instant.now()).emailForce(false).build();
        when(policy.eligible(event)).thenReturn(true);
        when(receipts.claim(event.eventId(),event.recipientId())).thenReturn(1);
        when(repository.saveAndFlush(any())).thenAnswer(call->{
            Notification notification=call.getArgument(0); ReflectionTestUtils.setField(notification,"id",UUID.randomUUID()); return notification;
        });
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            assertThat(call.getArgument(0,Object.class)).isInstanceOf(NotificationPersisted.class);
            throw new IllegalStateException("durable planner unavailable");
        }).when(publisher).publishEvent(any(Object.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            Runnable write=rabbit
                    ? ()->new NotificationEventListener(repository,badges,mapper,websocket,mock(MailProducer.class),service,receipts,policy,publisher).handle(event)
                    : ()->new TransactionalNotificationService(repository,mapper,badges,websocket,service,receipts,policy,publisher).persistInCurrentTransaction(event);
            assertThatThrownBy(write::run).isInstanceOf(IllegalStateException.class).hasMessage("durable planner unavailable");
            verify(publisher).publishEvent(any(Object.class));
            verify(policy,never()).schedule(any(),any(),any());
            verifyNoInteractions(websocket,badges);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
