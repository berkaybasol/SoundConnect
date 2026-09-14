package com.berkayb.soundconnect.modules.feed.musician.cursor;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MusicianFeedCursorCodecTest {
    private static final Instant ANCHOR = Instant.parse("2026-09-11T10:15:30Z");
    private static final Set<MusicianFeedItemType> TYPES = Set.of(
            MusicianFeedItemType.TRACK, MusicianFeedItemType.COLLAB);

    private MusicianFeedCursorCodec codec;
    private UUID viewer;
    private MusicianFeedCursorState state;

    @BeforeEach
    void setUp() {
        MusicianFeedProperties properties = new MusicianFeedProperties();
        properties.setCursorSecret("unit-test-musician-feed-secret-at-least-32-bytes");
        properties.setCursorTtl(Duration.ofHours(24));
        codec = new MusicianFeedCursorCodec(new ObjectMapper(), properties);
        viewer = UUID.randomUUID();
        state = new MusicianFeedCursorState(viewer, UUID.randomUUID(), ANCHOR,
                new MusicianFeedCursorState.CursorPosition(42_001L,
                        ANCHOR.minusSeconds(60), "TRACK:" + UUID.randomUUID()), 17);
    }

    @Test
    void signedCursorRoundTripsEveryPaginationInvariant() {
        String token = codec.encode(state, TYPES);

        MusicianFeedCursorState decoded = codec.decode(token, viewer, TYPES, ANCHOR.plusSeconds(5));

        assertThat(decoded).isEqualTo(state);
        assertThat(token).doesNotContain(viewer.toString()).doesNotContain("TRACK:");
    }

    @Test
    void venueCursorIsAudienceBoundBeforeReplayEvenForTheSameAccountAndCapabilities() {
        UUID venueId = UUID.randomUUID();
        var venueState = new MusicianFeedCursorState(viewer, state.feedSessionId(), ANCHOR,
                state.after(), 17, 17, "venue-context", MusicianFeedAnnouncementPlan.EMPTY, BackstageFeedAudience.VENUE, venueId);
        String venueToken = codec.encode(venueState, TYPES);
        assertThat(codec.decode(venueToken, viewer, TYPES, ANCHOR, "venue-context", BackstageFeedAudience.VENUE, venueId))
                .isEqualTo(venueState);
        assertThat(codec.decodeForReplay(venueToken, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE, venueId))
                .isEqualTo(venueState);
        assertInvalid(() -> codec.decodeForReplay(venueToken, viewer, TYPES, ANCHOR));
        assertInvalid(() -> codec.decodeForReplay(codec.encode(state, TYPES), viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE, venueId));
    }

    @Test
    void changedVenueIdentityCannotReplayOrContinueEvenWhenViewerAndRankingContextMatch() {
        UUID venueId = UUID.randomUUID();
        var venueState = new MusicianFeedCursorState(viewer, state.feedSessionId(), ANCHOR,
                state.after(), 17, 17, "venue-context", MusicianFeedAnnouncementPlan.EMPTY, BackstageFeedAudience.VENUE, venueId);
        String token = codec.encode(venueState, TYPES);
        UUID changedVenue = UUID.randomUUID();
        assertInvalid(() -> codec.decodeForReplay(token, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE, changedVenue));
        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR, "venue-context", BackstageFeedAudience.VENUE, changedVenue));
        assertInvalid(() -> codec.decodeForReplay(token, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE, null));
        assertInvalid(() -> codec.decodeForReplay(token, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE));
        assertThat(codec.decodeForReplay(token, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE, venueId))
                .isEqualTo(venueState);
        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR, "changed-mutable-preferences", BackstageFeedAudience.VENUE, venueId));
    }

    @Test
    void venueIdentityIsMandatoryWhenSigningAndCannotBeOmittedEvenFromAValidSignature() throws Exception {
        var missingIdentity = new MusicianFeedCursorState(viewer, state.feedSessionId(), ANCHOR,
                state.after(), 17, 17, "venue-context", MusicianFeedAnnouncementPlan.EMPTY, BackstageFeedAudience.VENUE);
        assertInvalid(() -> codec.encode(missingIdentity, TYPES));
        UUID venueId = UUID.randomUUID();
        var venueState = new MusicianFeedCursorState(viewer, state.feedSessionId(), ANCHOR,
                state.after(), 17, 17, "venue-context", MusicianFeedAnnouncementPlan.EMPTY, BackstageFeedAudience.VENUE, venueId);
        var mapper = new ObjectMapper();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                Base64.getUrlDecoder().decode(codec.encode(venueState, TYPES).split("\\.")[0]));
        body.remove("viewerProfileId");
        String missingClaim = sign(mapper.writeValueAsBytes(body));
        assertInvalid(() -> codec.decodeForReplay(missingClaim, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE, venueId));
    }

    @Test
    void listenerCursorRequiresItsProfileAndCannotCrossAnyOtherAudienceOrAccount() {
        UUID listenerId = UUID.randomUUID();
        var listener = new MusicianFeedCursorState(viewer, state.feedSessionId(), ANCHOR, state.after(), 17, 17,
                "listener-context", MusicianFeedAnnouncementPlan.EMPTY, BackstageFeedAudience.LISTENER, listenerId);
        String token = codec.encode(listener, TYPES);
        assertThat(codec.decode(token, viewer, TYPES, ANCHOR, "listener-context", BackstageFeedAudience.LISTENER, listenerId))
                .isEqualTo(listener);
        assertThat(codec.decodeForReplay(token, viewer, TYPES, ANCHOR, BackstageFeedAudience.LISTENER, listenerId)).isEqualTo(listener);
        assertInvalid(() -> codec.decodeForReplay(token, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE, listenerId));
        assertInvalid(() -> codec.decodeForReplay(token, viewer, TYPES, ANCHOR));
        assertInvalid(() -> codec.decodeForReplay(token, UUID.randomUUID(), TYPES, ANCHOR, BackstageFeedAudience.LISTENER, listenerId));
        assertInvalid(() -> codec.decodeForReplay(token, viewer, TYPES, ANCHOR, BackstageFeedAudience.LISTENER, UUID.randomUUID()));
        assertInvalid(() -> codec.decodeForReplay(token, viewer, TYPES, ANCHOR, BackstageFeedAudience.LISTENER));
        assertInvalid(() -> codec.decodeForReplay(codec.encode(state, TYPES), viewer, TYPES, ANCHOR, BackstageFeedAudience.LISTENER, listenerId));
    }

    @Test
    void missingAudienceKeepsExistingMusicianCursorsCompatibleWithoutAdmittingThemToVenue() throws Exception {
        var mapper = new ObjectMapper();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                Base64.getUrlDecoder().decode(codec.encode(state, TYPES).split("\\.")[0]));
        body.remove("audience");
        body.remove("viewerProfileId");
        String oldToken = sign(mapper.writeValueAsBytes(body));
        assertThat(codec.decode(oldToken, viewer, TYPES, ANCHOR)).isEqualTo(state);
        assertInvalid(() -> codec.decodeForReplay(oldToken, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE));
        body.put("audience", "VENUE");
        String wrongAlgorithm = sign(mapper.writeValueAsBytes(body));
        assertInvalid(() -> codec.decodeForReplay(wrongAlgorithm, viewer, TYPES, ANCHOR, BackstageFeedAudience.VENUE));
    }

    @Test
    void tamperingViewerOrAdvertisedRendererSetFailsClosed() {
        String token = codec.encode(state, TYPES);
        int flipAt = token.indexOf('.') / 2;
        char replacement = token.charAt(flipAt) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, flipAt) + replacement + token.substring(flipAt + 1);

        assertInvalid(() -> codec.decode(tampered, viewer, TYPES, ANCHOR));
        assertInvalid(() -> codec.decode(token, UUID.randomUUID(), TYPES, ANCHOR));
        assertInvalid(() -> codec.decode(token, viewer, Set.of(MusicianFeedItemType.TRACK), ANCHOR));
    }

    @Test
    void expiredOrImplausiblyFutureAnchorsFailClosed() {
        String token = codec.encode(state, TYPES);

        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR.plus(Duration.ofHours(24))));
        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR.plus(Duration.ofHours(24)).plusMillis(1)));
        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR.minusSeconds(31)));
    }

    @Test
    void cursorFromAnotherSchemaOrRankingVersionCannotCrossADeployBoundary() {
        MusicianFeedProperties properties = new MusicianFeedProperties();
        properties.setCursorSecret("unit-test-musician-feed-secret-at-least-32-bytes");
        MusicianFeedCursorCodec previous = new MusicianFeedCursorCodec(
                new ObjectMapper(), properties, 1, "musician-v0.9.0");
        String oldToken = previous.encode(state, TYPES);

        assertInvalid(() -> codec.decode(oldToken, viewer, TYPES, ANCHOR));
        assertInvalid(() -> codec.decodeForReplay(oldToken, viewer, TYPES, ANCHOR));
    }

    @Test
    void replayDecodeIgnoresOnlyMutableRankingContext() {
        String token = codec.encode(state, TYPES);

        MusicianFeedCursorState decoded = codec.decodeForReplay(token, viewer, TYPES, ANCHOR.plusSeconds(1));

        assertThat(decoded).isEqualTo(state);
        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR.plusSeconds(1), "changed-context"));
    }

    @Test
    void announcementPlanSurvivesSingleNormalItemPagesAndReplayDecodeWithoutRedrawing() {
        var plan = new MusicianFeedAnnouncementPlan(List.of(
                new MusicianFeedAnnouncementPlan.Entry(UUID.randomUUID(), 2),
                new MusicianFeedAnnouncementPlan.Entry(UUID.randomUUID(), 7),
                new MusicianFeedAnnouncementPlan.Entry(UUID.randomUUID(), 4)));
        var withPlan = new MusicianFeedCursorState(viewer, state.feedSessionId(), ANCHOR,
                state.after(), 1, 1, "frozen", plan);
        var types = Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.ANNOUNCEMENT);
        String token = codec.encode(withPlan, types);
        assertThat(token.length()).isLessThan(4096);
        assertThat(codec.decode(token, viewer, types, ANCHOR, "frozen")).isEqualTo(withPlan);
        assertThat(codec.decodeForReplay(token, viewer, types, ANCHOR.plusSeconds(10)).announcementPlan()).isEqualTo(plan);
        assertInvalid(() -> codec.encode(withPlan, TYPES));
    }

    @Test
    void evenSignedInvalidAnnouncementPlanShapesAreRejectedAndOldMissingPlansStayEmpty() throws Exception {
        String token = codec.encode(state, TYPES);
        var mapper = new ObjectMapper();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        payload.remove("announcementPlan");
        assertThat(codec.decode(sign(mapper.writeValueAsBytes(payload)), viewer, TYPES, ANCHOR).announcementPlan().entries()).isEmpty();
        var types = Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.ANNOUNCEMENT);
        var withPlan = new MusicianFeedCursorState(viewer, state.feedSessionId(), ANCHOR, state.after(), 1, 1, "0",
                new MusicianFeedAnnouncementPlan(List.of(new MusicianFeedAnnouncementPlan.Entry(UUID.randomUUID(), 1))));
        payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                Base64.getUrlDecoder().decode(codec.encode(withPlan, types).split("\\.")[0]));
        var entries = (com.fasterxml.jackson.databind.node.ArrayNode) payload.path("announcementPlan").path("entries");
        entries.add(entries.get(0).deepCopy());
        String duplicated = sign(mapper.writeValueAsBytes(payload));
        assertInvalid(() -> codec.decode(duplicated, viewer, types, ANCHOR));
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("unit-test-musician-feed-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(SoundConnectException.class, failure ->
                        assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
    }
}
