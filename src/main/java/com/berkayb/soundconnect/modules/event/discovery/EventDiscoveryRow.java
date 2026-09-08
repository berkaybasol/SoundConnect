package com.berkayb.soundconnect.modules.event.discovery;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Scalar read model: never initializes entity collections or private account fields. */
public record EventDiscoveryRow(UUID id, String title, String posterImage,
        UUID musicianProfileId, String musicianUsername, String musicianStageName,
        UUID bandId, String bandName, String manualPerformerName,
        UUID venueId, String venueName, String venueCity, String venueDistrict, String venueNeighborhood,
        LocalDate eventDate, LocalTime startTime, LocalTime endTime, String description) {
}
