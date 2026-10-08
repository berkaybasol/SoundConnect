package com.berkayb.soundconnect.modules.event.plan;

import java.time.LocalTime;
import java.util.UUID;

public record EventPlanTemplate(String title, String description, LocalTime startTime, LocalTime endTime,
        String posterImage, UUID musicianProfileId, UUID bandId, String manualPerformerName) { }
