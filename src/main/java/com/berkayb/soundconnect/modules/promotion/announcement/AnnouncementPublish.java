package com.berkayb.soundconnect.modules.promotion.announcement;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

public record AnnouncementPublish(@NotNull @PositiveOrZero Long expectedVersion,
                                  Instant startsAt, Instant endsAt) { }
