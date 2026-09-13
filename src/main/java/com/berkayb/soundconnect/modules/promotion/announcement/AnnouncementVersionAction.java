package com.berkayb.soundconnect.modules.promotion.announcement;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AnnouncementVersionAction(@NotNull @PositiveOrZero Long expectedVersion) { }
