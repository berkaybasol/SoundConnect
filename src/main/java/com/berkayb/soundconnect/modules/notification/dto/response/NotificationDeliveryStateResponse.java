package com.berkayb.soundconnect.modules.notification.dto.response;

import java.util.List;
import java.util.UUID;

/** No ownership/existence distinction is exposed for entries that should disappear. */
public record NotificationDeliveryStateResponse(List<UUID> dismissedIds) { }
