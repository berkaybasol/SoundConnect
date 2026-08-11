package com.berkayb.soundconnect.modules.collab.dto.request;

import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import jakarta.validation.constraints.*;

import java.util.UUID;

public record CollabReportCreateRequest(
        @NotNull UUID clientRequestId,
        @NotNull CollabReportReason reason,
        @Size(max = 500) String details
) {}
