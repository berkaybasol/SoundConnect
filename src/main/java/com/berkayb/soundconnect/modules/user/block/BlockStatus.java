package com.berkayb.soundconnect.modules.user.block;

public record BlockStatus(boolean blockedByMe, boolean interactionBlocked) {
    public static final BlockStatus AVAILABLE = new BlockStatus(false, false);
}
