package com.berkayb.soundconnect.modules.user.deletion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandDeletionDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountDeletionRequest(
        @NotBlank @Pattern(regexp = "DELETE") String confirmation,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 128) String currentPassword,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 8192) String googleIdToken,
        @Size(max = 100) List<@NotNull @Valid BandDeletionDecision> bandDecisions
) {
    public AccountDeletionRequest(String confirmation, String currentPassword, String googleIdToken) {
        this(confirmation, currentPassword, googleIdToken, null);
    }
    @Override public String toString() { return "AccountDeletionRequest[credentials=REDACTED]"; }
}
