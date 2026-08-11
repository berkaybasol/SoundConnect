package com.berkayb.soundconnect.modules.collab.support;

import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class CollabTimeProvider {
    public Instant now() { return Instant.now(); }
}
