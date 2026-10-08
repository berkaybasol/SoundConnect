package com.berkayb.soundconnect.modules.feed.musician.feedback;

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

/** Signed keyset positions are scoped to one viewer and this endpoint. */
@Component
public class MusicianFeedMutedAuthorsCursorCodec {
    private static final int VERSION = 1;
    private static final byte[] DOMAIN = "musician-muted-authors:v1\u0000".getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper mapper;
    private final MusicianFeedProperties properties;

    public MusicianFeedMutedAuthorsCursorCodec(ObjectMapper mapper, MusicianFeedProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public String encode(UUID viewerId, Position position) {
        try {
            byte[] body = mapper.writeValueAsBytes(new Payload(VERSION, viewerId,
                    position.anchor().toString(), position.mutedAt().toString(), position.feedbackId()));
            var encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(body) + "." + encoder.encodeToString(sign(body));
        } catch (Exception impossible) {
            throw new IllegalStateException("Cannot encode muted-author cursor", impossible);
        }
    }

    public Position decode(String token, UUID viewerId, Instant now) {
        try {
            if (token == null || token.isBlank() || token.length() > 2048) throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) throw invalid();
            byte[] body = Base64.getUrlDecoder().decode(parts[0]);
            if (!MessageDigest.isEqual(sign(body), Base64.getUrlDecoder().decode(parts[1]))) throw invalid();
            Payload payload = mapper.readValue(body, Payload.class);
            if (payload.version() != VERSION || viewerId == null || !viewerId.equals(payload.viewerId())
                    || payload.feedbackId() == null) throw invalid();
            Instant anchor = Instant.parse(payload.anchor());
            Instant mutedAt = Instant.parse(payload.mutedAt());
            if (anchor.isAfter(now.plusSeconds(30)) || !anchor.isAfter(now.minus(properties.getCursorTtl()))
                    || mutedAt.isAfter(anchor)) throw invalid();
            return new Position(anchor, mutedAt, payload.feedbackId());
        } catch (SoundConnectException known) {
            throw known;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getCursorSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(DOMAIN);
        return mac.doFinal(body);
    }

    private SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.MUSICIAN_FEED_CURSOR_INVALID);
    }

    public record Position(Instant anchor, Instant mutedAt, UUID feedbackId) { }
    private record Payload(int version, UUID viewerId, String anchor, String mutedAt, UUID feedbackId) { }
}
