package com.berkayb.soundconnect.modules.event.plan;
import java.util.UUID;
public record EventPlanAuthority(UUID organizerUserId, UUID venueId, UUID bandId, UUID musicianProfileId) { }
