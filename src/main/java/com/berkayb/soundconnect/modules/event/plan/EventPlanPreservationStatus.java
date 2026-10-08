package com.berkayb.soundconnect.modules.event.plan;

/** Preview reasons only; STARTED is derived from the event clock, never persisted in the ledger. */
public enum EventPlanPreservationStatus { OVERRIDDEN, SKIPPED, CANCELLED, STARTED }
