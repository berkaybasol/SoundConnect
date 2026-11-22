package com.berkayb.soundconnect.shared.exception;

import lombok.Getter;

@Getter
public class StudioApplicationRejectedException extends SoundConnectException {
    private final String businessName;
    private final String rejectionReason;

    public StudioApplicationRejectedException(String businessName, String rejectionReason) {
        super(ErrorType.STUDIO_APPLICATION_REJECTED);
        this.businessName = businessName;
        this.rejectionReason = rejectionReason;
    }
}
