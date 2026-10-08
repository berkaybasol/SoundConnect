package com.berkayb.soundconnect.modules.feed.musician.preference.dto;

import java.util.UUID;

/** Stable canonical identity; clients must never map a selection back by name. */
public record MusicianInstrumentSummary(UUID id, String name) {}
