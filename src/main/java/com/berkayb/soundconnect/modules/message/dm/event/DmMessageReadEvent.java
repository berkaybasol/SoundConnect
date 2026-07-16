package com.berkayb.soundconnect.modules.message.dm.event;

import java.util.UUID;

public record DmMessageReadEvent(UUID conversationId, UUID readerId) {
}
