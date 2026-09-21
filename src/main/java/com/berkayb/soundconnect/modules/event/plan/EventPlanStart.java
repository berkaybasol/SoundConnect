package com.berkayb.soundconnect.modules.event.plan;

import java.time.LocalDate;
import java.time.LocalTime;

/** Scalar projection: checking availability must never hydrate an Event before its write lock. */
public record EventPlanStart(LocalDate eventDate, LocalTime startTime) { }
