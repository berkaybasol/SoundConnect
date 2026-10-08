package com.berkayb.soundconnect.modules.notification.push.transport;

import java.time.Duration;

/** ACCEPTED means provider acceptance, never device display or user acknowledgement. */
public record PushSendResult(Outcome outcome, String providerMessageId, String errorCode,
                             Duration retryAfter) {
    public enum Outcome {
        ACCEPTED, RETRYABLE_FAILURE, INVALID_DEVICE, PERMANENT_FAILURE
    }

    public static PushSendResult accepted(String providerMessageId) {
        return new PushSendResult(Outcome.ACCEPTED, providerMessageId, null, null);
    }

    public static PushSendResult failed(Outcome outcome, String code, Duration retryAfter) {
        return new PushSendResult(outcome, null, code, retryAfter);
    }
}
