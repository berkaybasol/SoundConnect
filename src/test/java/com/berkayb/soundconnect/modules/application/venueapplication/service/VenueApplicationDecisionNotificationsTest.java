package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueApplicationDecisionNotificationsTest {
    @ParameterizedTest @ValueSource(strings={"APPROVED","REJECTED"})
    void deterministicPrivateDecisionUsesOnlyTransactionalNoMailContract(String state) {
        var sink=mock(TransactionalNotificationService.class);var service=new VenueApplicationDecisionNotifications(sink);
        var app=VenueApplication.builder().id(UUID.randomUUID()).applicant(User.builder().id(UUID.randomUUID()).build())
                .status(ApplicationStatus.valueOf(state)).decisionDate(LocalDateTime.of(2026,9,24,12,34)).venueName("private venue").phone("private phone").build();
        service.decided(app);service.decided(app);
        var capture=ArgumentCaptor.forClass(NotificationInboundEvent.class);verify(sink,times(2)).persistInCurrentTransaction(capture.capture());
        var a=capture.getAllValues().getFirst();var b=capture.getAllValues().getLast();
        assertThat(a.eventId()).isEqualTo(b.eventId());assertThat(a.emailForce()).isFalse();
        assertThat(a.type().name()).isEqualTo("VENUE_APPLICATION_"+state);
        assertThat(a.occurredAt()).isEqualTo(Instant.parse("2026-09-24T12:34:00Z"));
        assertThat(a.payload()).containsOnlyKeys("module","applicationId","applicantUserId","status","action");
        assertThat(a.toString()).doesNotContain("private venue","private phone");
    }
    @Test void pendingCannotPublishAResult() {
        var sink=mock(TransactionalNotificationService.class);
        assertThatThrownBy(()->new VenueApplicationDecisionNotifications(sink).decided(VenueApplication.builder().status(ApplicationStatus.PENDING).build()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sink);
    }
}
