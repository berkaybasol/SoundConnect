package com.berkayb.soundconnect.modules.notification.push.transport;

public interface PushTransport {
    PushSendResult send(PushEnvelope envelope);
}
