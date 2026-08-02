package com.berkayb.soundconnect.modules.studio.reservation.enums;

public enum StudioReservationStatus {
    PENDING_APPROVAL,
    CONFIRMED,
    REJECTED_BY_STUDIO,
    CANCELLED_BY_CUSTOMER,
    CANCELLED_BY_STUDIO,
    EXPIRED;

    public boolean isTerminal() {
        return this == REJECTED_BY_STUDIO
                || this == CANCELLED_BY_CUSTOMER
                || this == CANCELLED_BY_STUDIO
                || this == EXPIRED;
    }
}
