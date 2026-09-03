package com.berkayb.soundconnect.modules.profile.ListenerProfile.enums;

/**
 * Controls how a listener profile is exposed outside its owner and moderation
 * contexts. This is deliberately a profile concern rather than an account role
 * so Mainstage identity remains stable while the listener's showcase is hidden.
 */
public enum ListenerVisibilityMode {
	STANDARD,
	GHOST
}
