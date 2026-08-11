package com.berkayb.soundconnect.modules.collab.dto.request;

import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import jakarta.validation.constraints.*;

import java.util.Set;
import java.util.UUID;

public record CollabFilterRequest(
        CollabCadence cadence,
        UUID cityId,
        CollabWantedType wantedType,
        @Size(max = 50) Set<UUID> instrumentIds,
        @Size(max = 5) Set<CollabBranch> branches,
        @Size(max = 4) Set<ProfileType> publisherTypes,
        CollabPublishedWindow publishedWindow,
        @Size(max = 100) String q
) {}
