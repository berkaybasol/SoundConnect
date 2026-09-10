package com.berkayb.soundconnect.modules.user.deletion;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountDeletionRequest(
        @NotBlank @Pattern(regexp = "DELETE") String confirmation,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 128) String currentPassword,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 8192) String googleIdToken
) {
    @Override public String toString() { return "AccountDeletionRequest[credentials=REDACTED]"; }
}
