package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class MusicianFeedDeliveryTokenCodec {
    private static final int VERSION = 1;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final MusicianFeedProperties properties;

    public MusicianFeedDeliveryTokenCodec(ObjectMapper objectMapper, MusicianFeedProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String encode(MusicianFeedDeliveredItem delivery) {
        Claims claims = new Claims(VERSION, delivery.deliveryId(), delivery.viewerUserId(),
                delivery.feedSessionId(), delivery.itemId(), delivery.targetType(), delivery.targetId(),
                delivery.schemaVersion(), delivery.algorithmVersion(), delivery.absolutePosition(),
                delivery.expiresAt().toEpochMilli());
        try {
            byte[] body = objectMapper.writeValueAsBytes(claims);
            return ENCODER.encodeToString(body) + "." + ENCODER.encodeToString(sign(body));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign musician-feed delivery", exception);
        }
    }

    public Claims decode(String token, UUID expectedViewer, Instant now) {
        try {
            if (token == null || token.isBlank() || token.length() > 4096) throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) throw invalid();
            byte[] body = DECODER.decode(parts[0]);
            if (!MessageDigest.isEqual(sign(body), DECODER.decode(parts[1]))) throw invalid();
            Claims claims = objectMapper.readValue(body, Claims.class);
            if (claims.version() != VERSION || claims.deliveryId() == null
                    || claims.viewerUserId() == null || !claims.viewerUserId().equals(expectedViewer)
                    || claims.feedSessionId() == null || claims.itemId() == null || claims.itemId().isBlank()
                    || claims.targetType() == null || claims.targetId() == null
                    || claims.algorithmVersion() == null || claims.schemaVersion() < 1
                    || claims.absolutePosition() < 0
                    || !Instant.ofEpochMilli(claims.expiresAtEpochMillis()).isAfter(now)) throw invalid();
            return claims;
        } catch (SoundConnectException known) {
            throw known;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getDeliverySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(body);
    }

    private SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.BAD_REQUEST);
    }

    public record Claims(
            int version,
            UUID deliveryId,
            UUID viewerUserId,
            UUID feedSessionId,
            String itemId,
            String targetType,
            UUID targetId,
            int schemaVersion,
            String algorithmVersion,
            long absolutePosition,
            long expiresAtEpochMillis
    ) { }
}
