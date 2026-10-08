package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** Application-only access. This never changes general account or business authorization. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VenueApplicationSessionAccess {
    private final VenueApplicationRepository applications;
    private final VenueRepository venues;

    public Optional<UUID> findSessionApplication(User user) {
        if (!pending(user)) return Optional.empty();
        return applications.findFirstByApplicant_IdOrderByApplicationDateDescCreatedAtDescIdDesc(user.getId())
                .filter(application -> application.getStatus() == ApplicationStatus.PENDING
                        || application.getStatus() == ApplicationStatus.REJECTED)
                .map(VenueApplication::getId);
    }

    public boolean isAccessible(User user, UUID applicationId) {
        if (!usable(user) || applicationId == null) return false;
        var application = applications.findByIdAndApplicant_Id(applicationId, user.getId()).orElse(null);
        return permits(user, application);
    }

    public VenueApplication requireAccessible(User user, UUID applicationId) {
        var application = usable(user) && applicationId != null
                ? applications.findByIdAndApplicant_Id(applicationId, user.getId()).orElse(null) : null;
        if (!permits(user, application)) throw new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND);
        return application;
    }

    public boolean permits(User user, VenueApplication application) {
        if (!usable(user) || application == null || application.getApplicant() == null
                || !user.getId().equals(application.getApplicant().getId())) return false;
        var latest = applications.findFirstByApplicant_IdOrderByApplicationDateDescCreatedAtDescIdDesc(user.getId());
        if (latest.isEmpty() || !application.getId().equals(latest.get().getId())) return false;
        if (pending(user)) return application.getStatus() == ApplicationStatus.PENDING
                || application.getStatus() == ApplicationStatus.REJECTED;
        return user.getStatus() == UserStatus.ACTIVE && application.getStatus() == ApplicationStatus.APPROVED
                && user.getRoles() != null && user.getRoles().stream().anyMatch(role -> "ROLE_VENUE".equals(role.getName()))
                && application.getApprovedVenue() != null
                && venues.findByIdAndOwnerId(application.getApprovedVenue().getId(), user.getId())
                    .filter(venue -> venue.getStatus() == VenueStatus.APPROVED).isPresent();
    }

    private static boolean usable(User user) {
        return user != null && user.getId() != null && user.getErasedAt() == null && Boolean.TRUE.equals(user.getEmailVerified());
    }
    private static boolean pending(User user) {
        return usable(user) && user.getStatus() == UserStatus.PENDING_VENUE_REQUEST
                && (user.getRoles() == null || user.getRoles().isEmpty())
                && (user.getPermissions() == null || user.getPermissions().isEmpty());
    }
}
