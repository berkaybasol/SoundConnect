package com.berkayb.soundconnect.modules.collab.dto.request;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record CollabApplicationCreateRequest(
        @NotNull UUID clientRequestId,
        @NotNull UUID applicantActorId,
        @NotBlank @Size(min = 7, max = 32) String phoneNumber,
        @Size(max = 500) String message
) {}
