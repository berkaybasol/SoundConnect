package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Source-bound reads, exact ACKs and promotion; no network or shared database. */
@ExtendWith(MockitoExtension.class)
class VenueApplicationSessionServiceTest {
    @Mock VenueApplicationRepository applications;
    @Mock UserRepository users;
    @Mock VenueApplicationSessionAccess access;
    @Mock NotificationRepository notifications;
    @Mock NotificationService notificationService;
    @Mock JwtTokenProvider tokens;
    @Mock EntityManager entityManager;
    @InjectMocks VenueApplicationSessionService service;

    private final UUID userId = UUID.randomUUID(), applicationId = UUID.randomUUID();
    private User user;
    private VenueApplication application;

    @BeforeEach void sourceFixture() {
        user = User.builder().id(userId).username("owned-applicant").emailVerified(true)
                .status(UserStatus.PENDING_VENUE_REQUEST).roles(Set.of()).permissions(Set.of()).build();
        application = VenueApplication.builder().id(applicationId).applicant(user)
                .venueName("Owned venue").venueAddress("private address").phone("private phone")
                .status(ApplicationStatus.REJECTED).applicationDate(LocalDateTime.of(2026, 9, 24, 9, 0))
                .decisionDate(LocalDateTime.of(2026, 9, 24, 10, 0)).build();
    }

    private void allowSource() {
        when(applications.findOwnedForUpdate(applicationId, userId)).thenReturn(Optional.of(application));
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(access.permits(user, application)).thenReturn(true);
    }

    private Notification notice() {
        Notification n = Notification.builder().recipientId(userId).type(NotificationType.VENUE_APPLICATION_REJECTED)
                .title("Mekân başvurusu").message("Mekân başvurun reddedildi.").read(false)
                .occurredAt(Instant.parse("2026-09-24T10:00:00Z"))
                .payload(new HashMap<>(Map.of("module", "VENUE_APPLICATION", "applicationId", applicationId.toString(),
                        "applicantUserId", userId.toString(), "action", "APPLICATION_REJECTED", "status", "REJECTED")))
                .build();
        n.setId(UUID.randomUUID());
        return n;
    }

    private void available(Notification n) {
        when(notifications.findByIdAndRecipientId(n.getId(), userId)).thenReturn(Optional.of(n));
    }

    private static void failure(Runnable operation, ErrorType error) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(SoundConnectException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(error));
    }

    @Test void detailUsesOwnedFreshSourceAndUtcWithoutAcknowledgingAnything() {
        allowSource();
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            var result = service.detail(userId, applicationId);
            assertThat(result.id()).isEqualTo(applicationId);
            assertThat(result.applicantUserId()).isEqualTo(userId);
            assertThat(result.status()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(result.venueName()).isEqualTo("Owned venue");
            assertThat(result.applicationDate()).isEqualTo(Instant.parse("2026-09-24T09:00:00Z"));
            assertThat(result.decisionDate()).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
            assertThat(result.venueId()).isNull();
        } finally { TimeZone.setDefault(original); }
        var order = inOrder(applications, entityManager, users, access);
        order.verify(applications).findOwnedForUpdate(applicationId, userId);
        order.verify(entityManager).refresh(application, LockModeType.PESSIMISTIC_WRITE);
        order.verify(users).findByIdForUpdate(userId);
        order.verify(entityManager).refresh(user, LockModeType.PESSIMISTIC_WRITE);
        order.verify(access).permits(user, application);
        verifyNoInteractions(notifications, notificationService, tokens);
    }

    @Test void missingOrForeignApplicationNeverReadsTheAccountOrNotification() {
        when(applications.findOwnedForUpdate(applicationId, userId)).thenReturn(Optional.empty());
        failure(() -> service.detail(userId, applicationId), ErrorType.VENUE_APPLICATION_NOT_FOUND);
        verifyNoInteractions(users, access, entityManager, notifications, notificationService, tokens);
    }

    @Test void disappearedAccountCannotUsePreviouslyResolvedApplication() {
        when(applications.findOwnedForUpdate(applicationId, userId)).thenReturn(Optional.of(application));
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.empty());
        failure(() -> service.detail(userId, applicationId), ErrorType.VENUE_APPLICATION_NOT_FOUND);
        verifyNoInteractions(access, notifications, notificationService, tokens);
    }

    @Test void revokedSourceAccessPreventsLookupAckAndTokenIssuance() {
        allowSource();
        when(access.permits(user, application)).thenReturn(false);
        failure(() -> service.read(userId, applicationId, UUID.randomUUID()), ErrorType.VENUE_APPLICATION_NOT_FOUND);
        failure(() -> service.promote(userId, applicationId), ErrorType.VENUE_APPLICATION_NOT_FOUND);
        verifyNoInteractions(notifications, notificationService, tokens);
    }

    @Test void exactLookupIsReadOnlyAndDoesNotExposeMutableSourcePayload() {
        allowSource();
        var notice = notice(); available(notice);
        var result = service.notification(userId, applicationId, notice.getId());
        assertThat(result.id()).isEqualTo(notice.getId());
        assertThat(result.recipientId()).isEqualTo(userId);
        assertThat(result.read()).isFalse();
        assertThat(result.payload()).containsEntry("applicationId", applicationId.toString());
        assertThatThrownBy(() -> result.payload().put("reason", "injected"))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(notificationService, tokens);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipient", "type", "nullPayload", "module", "applicationId", "applicantUserId",
            "status", "action", "missingApplicationId", "malformedApplicationId"})
    void lookupAndAckRejectCrossSourceOrForgedDecision(String drift) {
        allowSource();
        var notice = notice();
        switch (drift) {
            case "recipient" -> notice.setRecipientId(UUID.randomUUID());
            case "type" -> notice.setType(NotificationType.DM_NEW_MESSAGE);
            case "nullPayload" -> notice.setPayload(null);
            case "module" -> notice.getPayload().put("module", "ARTIST_VENUE_LINK");
            case "applicationId", "applicantUserId" -> notice.getPayload().put(drift, UUID.randomUUID().toString());
            case "status" -> notice.getPayload().put("status", "APPROVED");
            case "action" -> notice.getPayload().put("action", "APPLICATION_APPROVED");
            case "missingApplicationId" -> notice.getPayload().remove("applicationId");
            case "malformedApplicationId" -> notice.getPayload().put("applicationId", "1-1-1-1-1");
            default -> throw new AssertionError(drift);
        }
        available(notice);
        failure(() -> service.notification(userId, applicationId, notice.getId()), ErrorType.NOTIFICATION_NOT_FOUND);
        failure(() -> service.read(userId, applicationId, notice.getId()), ErrorType.NOTIFICATION_NOT_FOUND);
        verifyNoInteractions(notificationService, tokens);
    }

    @Test void exactReadAcknowledgesOneIdOnlyAndNeverReadAllOrDm() {
        allowSource();
        var notice = notice(); available(notice);
        service.read(userId, applicationId, notice.getId());
        verify(notificationService).markAsRead(userId, notice.getId());
        verifyNoMoreInteractions(notificationService);
        verifyNoInteractions(tokens);
    }

    @Test void missingNotificationDoesNotAcknowledgeAnyId() {
        allowSource();
        var id = UUID.randomUUID();
        when(notifications.findByIdAndRecipientId(id, userId)).thenReturn(Optional.empty());
        failure(() -> service.read(userId, applicationId, id), ErrorType.NOTIFICATION_NOT_FOUND);
        verifyNoInteractions(notificationService, tokens);
    }

    @Test void deliveredReconciliationOnlyReturnsRequestedIneligibleIdsWithoutAck() {
        allowSource();
        var unread = notice(); available(unread);
        var read = notice(); read.setRead(true); available(read);
        var foreignSource = notice(); foreignSource.getPayload().put("applicationId", UUID.randomUUID().toString());
        available(foreignSource);
        var dm = notice(); dm.setType(NotificationType.DM_NEW_MESSAGE); available(dm);
        var missing = UUID.randomUUID();
        when(notifications.findByIdAndRecipientId(missing, userId)).thenReturn(Optional.empty());
        var result = service.dismissed(userId, applicationId,
                List.of(unread.getId(), read.getId(), foreignSource.getId(), dm.getId(), missing, read.getId()));
        assertThat(result).containsExactly(read.getId(), foreignSource.getId(), dm.getId(), missing);
        verify(notifications, times(1)).findByIdAndRecipientId(read.getId(), userId);
        verifyNoInteractions(notificationService, tokens);
    }

    @Test void invalidReconciliationBatchFailsBeforeReadingAnySource() {
        failure(() -> service.dismissed(userId, applicationId, null), ErrorType.VALIDATION_ERROR);
        failure(() -> service.dismissed(userId, applicationId, Arrays.asList(UUID.randomUUID(), null)), ErrorType.VALIDATION_ERROR);
        failure(() -> service.dismissed(userId, applicationId,
                IntStream.range(0, 101).mapToObj(ignored -> UUID.randomUUID()).toList()), ErrorType.VALIDATION_ERROR);
        verifyNoInteractions(applications, users, access, notifications, notificationService, tokens, entityManager);
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "REJECTED"})
    void nonApprovedApplicationCannotIssueOrdinarySession(String status) {
        application.setStatus(ApplicationStatus.valueOf(status));
        allowSource();
        failure(() -> service.promote(userId, applicationId), ErrorType.INVALID_APPLICATION_STATUS);
        verifyNoInteractions(tokens, notificationService);
    }

    @Test void approvedPromotionUsesExactVenueAndCurrentServerRolesWithOrdinaryIssuer() {
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.builder().id(UUID.randomUUID()).name("ROLE_VENUE").permissions(Set.of()).build(),
                Role.builder().id(UUID.randomUUID()).name("ROLE_ADMIN").permissions(Set.of()).build()));
        application.setStatus(ApplicationStatus.APPROVED);
        var venue = Venue.builder().id(UUID.randomUUID()).build();
        application.setApprovedVenue(venue);
        allowSource();
        when(tokens.generateToken(any(UserDetailsImpl.class))).thenReturn("ordinary-signed-token");
        var result = service.promote(userId, applicationId);
        assertThat(result.token()).isEqualTo("ordinary-signed-token");
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.sessionScope()).isNull();
        assertThat(result.applicationId()).isNull();
        assertThat(result.roles()).containsExactlyInAnyOrder("ROLE_VENUE", "ROLE_ADMIN");
        assertThat(result.admin()).isTrue();
        var principal = ArgumentCaptor.forClass(UserDetailsImpl.class);
        verify(tokens).generateToken(principal.capture());
        assertThat(principal.getValue().getUser()).isSameAs(user);
        verify(tokens, never()).generateVenueApplicationToken(any(), any());
        verify(entityManager).refresh(venue, LockModeType.PESSIMISTIC_READ);
        verifyNoInteractions(notifications, notificationService);
    }
}
