package com.berkayb.soundconnect.modules.user.service;

import java.util.UUID;

public record AccountAccessChanged(UUID userId, boolean suspended, long sessionVersion) {}
