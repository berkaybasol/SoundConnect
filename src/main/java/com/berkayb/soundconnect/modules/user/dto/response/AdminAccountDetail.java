package com.berkayb.soundconnect.modules.user.dto.response;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.Instant;
import java.util.UUID;

public record AdminAccountDetail(@JsonUnwrapped AdminAccountSummary account, String publicCode,
                                Instant createdAt, boolean emailVerified, ApplicationStatus lastApplicationStatus,
                                ProfileTarget profileTarget, long accessRevision, boolean canManageAccess,
                                AccessDecision lastAccessDecision) {
    public record ProfileTarget(ProfileType type, UUID profileId) {}
    public record AccessDecision(boolean suspended, String reason, String actorUsername, Instant decidedAt) {}
}
