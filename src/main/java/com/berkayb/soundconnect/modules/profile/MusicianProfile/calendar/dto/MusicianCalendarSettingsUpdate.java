package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record MusicianCalendarSettingsUpdate(
		@NotNull Boolean visible,
		@NotNull @PositiveOrZero @Max(Long.MAX_VALUE - 1) Long version
) {}
