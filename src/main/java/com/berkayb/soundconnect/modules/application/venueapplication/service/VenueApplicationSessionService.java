package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationDecisionDto;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.push.VenueApplicationPushPresentation;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class VenueApplicationSessionService {
    private final VenueApplicationRepository applications;
    private final UserRepository users;
    private final VenueApplicationSessionAccess access;
    private final NotificationRepository notifications;
    private final NotificationService notificationService;
    private final JwtTokenProvider tokens;
    private final EntityManager entityManager;

    public VenueApplicationDecisionDto detail(UUID userId, UUID applicationId) {
        var source=lock(userId, applicationId);
        var app=source.application();
        return new VenueApplicationDecisionDto(app.getId(), userId, app.getStatus(), app.getVenueName(),
                app.getApplicationDate().toInstant(ZoneOffset.UTC),
                app.getDecisionDate()==null?null:app.getDecisionDate().toInstant(ZoneOffset.UTC),
                app.getApprovedVenue()==null?null:app.getApprovedVenue().getId());
    }

    public LoginResponse promote(UUID userId, UUID applicationId, long authenticatedSessionVersion) {
        var source=lock(userId, applicationId);
        // The request may have passed its HTTP filter just before a reset.
        // Never upgrade that old credential into the newly committed revision.
        if (source.user().getSessionVersion() != authenticatedSessionVersion)
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if(source.application().getStatus()!=ApplicationStatus.APPROVED)
            throw new SoundConnectException(ErrorType.INVALID_APPLICATION_STATUS);
        var principal=new UserDetailsImpl(source.user());
        return LoginResponse.fromUser(tokens.generateToken(principal), source.user(), false);
    }

    public NotificationResponseDto notification(UUID userId, UUID applicationId, UUID notificationId) {
        var source=lock(userId, applicationId);
        var n=notifications.findByIdAndRecipientId(notificationId,userId)
                .filter(value->matches(value,source.application(),userId))
                .orElseThrow(()->new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND));
        return new NotificationResponseDto(n.getId(),n.getRecipientId(),n.getType(),n.getTitle(),n.getMessage(),
                n.isRead(),n.getOccurredAt(),Map.copyOf(n.getPayload()));
    }

    public void read(UUID userId, UUID applicationId, UUID notificationId) {
        notification(userId,applicationId,notificationId);
        notificationService.markAsRead(userId,notificationId);
    }

    public List<UUID> dismissed(UUID userId, UUID applicationId, List<UUID> ids) {
        if(ids==null || ids.size()>100 || ids.stream().anyMatch(Objects::isNull))
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        var source=lock(userId, applicationId);
        return new LinkedHashSet<>(ids).stream().filter(id->notifications.findByIdAndRecipientId(id,userId)
                .filter(n->!n.isRead() && matches(n,source.application(),userId)).isEmpty()).toList();
    }

    private static boolean matches(Notification n, VenueApplication source, UUID userId) {
        var p=n.getPayload();
        return n.getRecipientId().equals(userId) && VenueApplicationPushPresentation.TYPES.contains(n.getType())
                && p!=null && "VENUE_APPLICATION".equals(p.get("module"))
                && source.getId().equals(NotificationDeliveryPolicy.uuid(p,"applicationId"))
                && userId.equals(NotificationDeliveryPolicy.uuid(p,"applicantUserId"))
                && source.getStatus().name().equals(p.get("status"))
                && ("APPLICATION_"+source.getStatus()).equals(p.get("action"))
                && n.getType().name().equals("VENUE_APPLICATION_"+source.getStatus());
    }

    private Source lock(UUID userId, UUID applicationId) {
        var application=applications.findOwnedForUpdate(applicationId,userId)
                .orElseThrow(()->new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
        entityManager.refresh(application,LockModeType.PESSIMISTIC_WRITE);
        var user=users.findByIdForUpdate(userId)
                .orElseThrow(()->new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
        entityManager.refresh(user,LockModeType.PESSIMISTIC_WRITE);
        if(application.getApprovedVenue()!=null)
            entityManager.refresh(application.getApprovedVenue(),LockModeType.PESSIMISTIC_READ);
        if(!access.permits(user,application)) throw new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND);
        return new Source(application,user);
    }
    private record Source(VenueApplication application, User user) { }
}
