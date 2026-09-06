package com.berkayb.soundconnect.modules.event.enums;

/**
 * Event-scoped performer consent. This is deliberately separate from the
 * long-lived artist/venue connection lifecycle.
 */
public enum EventPerformerApprovalStatus {
	NOT_REQUIRED,
	PENDING,
	APPROVED,
	REJECTED
}
