package com.berkayb.soundconnect.modules.feed.musician.cursor;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class MusicianFeedCursorCodec {
    private static final int VERSION = 2;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final MusicianFeedProperties properties;
    private final int schemaVersion;
    private final String algorithmVersion;

    @Autowired
    public MusicianFeedCursorCodec(ObjectMapper objectMapper, MusicianFeedProperties properties) {
        this(objectMapper, properties, MusicianFeedService.SCHEMA_VERSION,
                MusicianFeedService.ALGORITHM_VERSION);
    }

    MusicianFeedCursorCodec(ObjectMapper objectMapper, MusicianFeedProperties properties,
                            int schemaVersion, String algorithmVersion) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.schemaVersion = schemaVersion;
        this.algorithmVersion = algorithmVersion;
    }

    public String encode(
            MusicianFeedCursorState state,
            Set<MusicianFeedItemType> supportedTypes
    ) {
        var after = state.after();
        CursorPayload payload = new CursorPayload(VERSION, schemaVersion, algorithmVersion,
                state.viewerUserId(), state.feedSessionId(),
                state.anchor().toEpochMilli(), after.rankKey(), after.occurredAt().toEpochMilli(),
                after.itemId(), state.deliveredOrganicCount(), state.deliveredItemCount(),
                state.rankingContextVersion(), supportedTypesHash(supportedTypes));
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            byte[] signature = sign(body);
            return ENCODER.encodeToString(body) + "." + ENCODER.encodeToString(signature);
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    public MusicianFeedCursorState decode(
            String token,
            UUID expectedViewer,
            Set<MusicianFeedItemType> supportedTypes,
            Instant now,
            String expectedRankingContextVersion
    ) {
        return decodeInternal(token, expectedViewer, supportedTypes, now,
                expectedRankingContextVersion, true, true);
    }

    /**
     * Verifies immutable/security and current wire-contract claims while
     * deferring only mutable ranking-context validation. Callers may use this
     * solely to locate an already committed response from the current deploy
     * contract; new candidate generation still requires strict decode.
     */
    public MusicianFeedCursorState decodeForReplay(
            String token,
            UUID expectedViewer,
            Set<MusicianFeedItemType> supportedTypes,
            Instant now
    ) {
        return decodeInternal(token, expectedViewer, supportedTypes, now, null, false, true);
    }

    private MusicianFeedCursorState decodeInternal(
            String token,
            UUID expectedViewer,
            Set<MusicianFeedItemType> supportedTypes,
            Instant now,
            String expectedRankingContextVersion,
            boolean enforceRankingContext,
            boolean enforceCurrentContract
    ) {
        try {
            if (token == null || token.isBlank() || token.length() > 4096) throw invalidCursor();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) throw invalidCursor();
            byte[] body = DECODER.decode(parts[0]);
            byte[] suppliedSignature = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(sign(body), suppliedSignature)) throw invalidCursor();
            CursorPayload payload = objectMapper.readValue(body, CursorPayload.class);
            if (payload.version() != VERSION
                    || (enforceCurrentContract && (payload.schemaVersion() != schemaVersion
                        || !algorithmVersion.equals(payload.algorithmVersion())))
                    || !expectedViewer.equals(payload.viewerUserId())
                    || !supportedTypesHash(supportedTypes).equals(payload.supportedTypesHash())
                    || (enforceRankingContext
                        && !Objects.equals(expectedRankingContextVersion, payload.rankingContextVersion()))
                    || payload.feedSessionId() == null || payload.lastItemId() == null
                    || payload.lastItemId().isBlank() || payload.deliveredOrganicCount() < 0
                    || payload.deliveredItemCount() < payload.deliveredOrganicCount()) {
                throw invalidCursor();
            }
            Instant anchor = Instant.ofEpochMilli(payload.anchorEpochMillis());
            if (anchor.isAfter(now.plusSeconds(30))
                    || !anchor.isAfter(now.minus(properties.getCursorTtl()))) {
                throw invalidCursor();
            }
            return new MusicianFeedCursorState(payload.viewerUserId(), payload.feedSessionId(), anchor,
                    new MusicianFeedCursorState.CursorPosition(payload.lastRankKey(),
                            Instant.ofEpochMilli(payload.lastOccurredAtEpochMillis()), payload.lastItemId()),
                    payload.deliveredOrganicCount(), payload.deliveredItemCount(),
                    payload.rankingContextVersion());
        } catch (SoundConnectException known) {
            throw known;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    public MusicianFeedCursorState decode(String token, UUID expectedViewer,
                                          Set<MusicianFeedItemType> supportedTypes, Instant now) {
        return decode(token, expectedViewer, supportedTypes, now, "0");
    }

    public String supportedTypesHash(Set<MusicianFeedItemType> supportedTypes) {
        String canonical = supportedTypes.stream().map(Enum::name).sorted(Comparator.naturalOrder())
                .reduce((left, right) -> left + "," + right).orElse("");
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getCursorSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(body);
    }

    private SoundConnectException invalidCursor() {
        return new SoundConnectException(ErrorType.MUSICIAN_FEED_CURSOR_INVALID);
    }

    private record CursorPayload(
            int version,
            int schemaVersion,
            String algorithmVersion,
            UUID viewerUserId,
            UUID feedSessionId,
            long anchorEpochMillis,
            long lastRankKey,
            long lastOccurredAtEpochMillis,
            String lastItemId,
            long deliveredOrganicCount,
            long deliveredItemCount,
            String rankingContextVersion,
            String supportedTypesHash
    ) { }
}
