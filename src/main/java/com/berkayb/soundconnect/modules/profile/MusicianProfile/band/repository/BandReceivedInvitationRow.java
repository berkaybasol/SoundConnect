package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import java.util.UUID;

public record BandReceivedInvitationRow(UUID bandId, String bandName, UUID profilePictureMediaId, UUID invitationId) {}
