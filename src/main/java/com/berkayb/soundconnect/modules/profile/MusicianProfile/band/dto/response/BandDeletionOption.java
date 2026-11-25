package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response;

import java.util.List;
import java.util.UUID;

public record BandDeletionOption(UUID bandId, String bandName, List<Successor> successors) {
    public record Successor(UUID userId, String username) {}
}
