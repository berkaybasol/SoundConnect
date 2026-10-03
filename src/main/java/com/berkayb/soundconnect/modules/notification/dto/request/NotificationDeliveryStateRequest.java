package com.berkayb.soundconnect.modules.notification.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** A bounded set of notifications currently displayed by this installation. */
public record NotificationDeliveryStateRequest(
        @NotNull @Size(max = 100) List<@NotNull UUID> notificationIds
) { }
