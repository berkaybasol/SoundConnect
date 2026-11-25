package com.berkayb.soundconnect.modules.notification.support;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.berkayb.soundconnect.modules.notification.enums.NotificationType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewRequestNotificationContactTest {
    private final UUID owner = UUID.randomUUID(), requester = UUID.randomUUID(), stranger = UUID.randomUUID();
    private final UUID listing = UUID.randomUUID(), application = UUID.randomUUID(), job = UUID.randomUUID();
    private final UUID review = UUID.randomUUID(), studio = UUID.randomUUID(), room = UUID.randomUUID(), reservation = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-11-24T10:00:00Z");
    private final AccountDeliveryFence accounts = mock(AccountDeliveryFence.class);
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    private final NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);

    @BeforeEach void setup() {
        jdbc.execute("create table tbl_collab (id uuid primary key, owner_user_id uuid)");
        jdbc.execute("create table tbl_collab_application (id uuid primary key, collab_id uuid, applicant_user_id uuid)");
        jdbc.execute("create table tbl_collab_job (id uuid primary key, publisher_user_id uuid, applicant_user_id uuid)");
        jdbc.execute("create table tbl_collab_review (id uuid primary key, job_id uuid, reviewer_user_id uuid)");
        jdbc.execute("create table tbl_studio_profile (id uuid primary key, user_id uuid)");
        jdbc.execute("create table tbl_studio_room (id uuid primary key, studio_profile_id uuid)");
        jdbc.execute("create table tbl_studio_room_reservation (id uuid primary key, room_id uuid, requester_id uuid)");
        jdbc.update("insert into tbl_collab values (?, ?)", listing, owner);
        jdbc.update("insert into tbl_collab_application values (?, ?, ?)", application, listing, requester);
        jdbc.update("insert into tbl_collab_job values (?, ?, ?)", job, owner, requester);
        jdbc.update("insert into tbl_collab_review values (?, ?, ?)", review, job, requester);
        jdbc.update("insert into tbl_studio_profile values (?, ?)", studio, owner);
        jdbc.update("insert into tbl_studio_room values (?, ?)", room, studio);
        jdbc.update("insert into tbl_studio_room_reservation values (?, ?, ?)", reservation, room, requester);
    }

    @Test void eachContactRechecksTheOriginalTimeAndPersistedPairAtDelivery() {
        var routes = Map.of(
                COLLAB_APPLICATION_RECEIVED, Map.of("listingId", listing, "applicationId", application),
                COLLAB_REVIEW_RECEIVED, Map.of("jobId", job, "reviewId", review),
                STUDIO_RESERVATION_CREATED, Map.of("roomId", room, "reservationId", reservation),
                STUDIO_RESERVATION_CONFLICTING_REQUESTS, Map.of("roomId", room, "reservationId", reservation));
        for (var route : routes.entrySet()) {
            var payload = Map.<String, Object>copyOf(route.getValue());
            assertThat(eligible(route.getKey(), owner, payload)).isTrue();
            assertThat(eligible(route.getKey(), stranger, payload)).isFalse();
        }
        when(accounts.contactEventSuppressed(requester, owner, occurredAt)).thenReturn(true);
        routes.forEach((type, route) -> assertThat(eligible(type, owner, Map.copyOf(route))).isFalse());
        verify(accounts, times(8)).contactEventSuppressed(requester, owner, occurredAt);
    }

    @Test void missingSourceStaysSuppressedAndAnExistingReservationCancellationRemainsEligible() {
        assertThat(eligible(COLLAB_APPLICATION_RECEIVED, owner, Map.of())).isFalse();
        assertThat(eligible(STUDIO_RESERVATION_CREATED, owner, Map.of("roomId", room, "reservationId", listing))).isFalse();
        assertThat(eligible(STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER, owner, Map.of())).isTrue();
        verifyNoInteractions(accounts);
    }

    private boolean eligible(NotificationType type, UUID recipient, Map<String, Object> payload) {
        var event = NotificationInboundEvent.builder().recipientId(recipient).type(type)
                .payload(payload).occurredAt(occurredAt).build();
        return NewRequestNotificationContact.eligible(named, accounts, event);
    }
}
