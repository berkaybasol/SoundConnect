package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response;

import java.util.UUID;

public record BandReceivedInvitationResponseDto(UUID bandId, String bandName, String profilePicture,
                                                String status, UUID invitationId) {}
