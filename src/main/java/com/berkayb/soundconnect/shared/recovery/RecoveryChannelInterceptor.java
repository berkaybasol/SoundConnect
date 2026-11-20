package com.berkayb.soundconnect.shared.recovery;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

/** Covers CONNECT/SEND/SUBSCRIBE and outbound messages; disconnect can clean up. */
public final class RecoveryChannelInterceptor implements ChannelInterceptor {
    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (RecoveryRuntime.isHeld()
                && StompHeaderAccessor.wrap(message).getCommand() != StompCommand.DISCONNECT) {
            throw new MessageDeliveryException("RECOVERY_HOLD_AUTHORITY_UNPROVEN");
        }
        return message;
    }
}
