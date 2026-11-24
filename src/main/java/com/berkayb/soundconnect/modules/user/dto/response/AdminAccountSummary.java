package com.berkayb.soundconnect.modules.user.dto.response;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;

import java.util.UUID;

public record AdminAccountSummary(UUID userId, String username, ProfileType profileType, UserStatus status,
                                  boolean suspended) {}
