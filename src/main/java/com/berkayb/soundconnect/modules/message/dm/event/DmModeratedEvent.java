package com.berkayb.soundconnect.modules.message.dm.event;

import java.util.Set;
import java.util.UUID;

public record DmModeratedEvent(Set<UUID> participants) {
    public DmModeratedEvent { participants = Set.copyOf(participants); }
}
