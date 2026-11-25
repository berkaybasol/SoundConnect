package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request;

import java.util.UUID;

/** Applied only in the transaction which erases the authenticated founder's account. */
public record BandDeletionDecision(UUID bandId, Action action, UUID successorUserId) {
    public enum Action { TRANSFER, CLOSE }
}
