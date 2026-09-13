package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicianFeedDeliveryLookupTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final MusicianFeedProperties properties = properties();
    private final MusicianFeedDeliveryTokenCodec tokens = new MusicianFeedDeliveryTokenCodec(
            new ObjectMapper().findAndRegisterModules(), properties);
    private final MusicianFeedDeliveryLookup lookup = new MusicianFeedDeliveryLookup(jdbc, tokens);
    private final Instant now = Instant.parse("2026-09-13T12:00:00Z");
    private final UUID viewer = UUID.randomUUID(), session = UUID.randomUUID(), target = UUID.randomUUID();
    private final MusicianFeedDeliveredItem delivered = new MusicianFeedDeliveredItem(
            UUID.randomUUID(), viewer, session, "ANNOUNCEMENT:" + target, MusicianFeedItemType.ANNOUNCEMENT,
            "ANNOUNCEMENT", target, null, null, "PLATFORM_ANNOUNCEMENT", Set.of(MusicianFeedFeedbackAction.HIDE),
            1, "musician-v1.1.0", 3, null, "{\"payload\":{\"omitted\":true}}",
            now.minusSeconds(1), now.plusSeconds(60), now.plusSeconds(120), MusicianFeedLane.SYSTEM);

    @Test
    void requiresViewerScopedUnexpiredLedgerEvidenceAndMapsEveryField() throws Exception {
        ResultSet row = storedRow();
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<MusicianFeedDeliveredItem>>any())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            SqlParameterSource parameters = call.getArgument(1);
            assertThat(sql).contains("id=:id", "viewer_user_id=:viewerId", "expires_at>:now");
            assertThat(parameters.getValue("id")).isEqualTo(delivered.deliveryId());
            assertThat(parameters.getValue("viewerId")).isEqualTo(viewer);
            assertThat(parameters.getValue("now")).isEqualTo(Timestamp.from(now));
            RowMapper<MusicianFeedDeliveredItem> mapper = call.getArgument(2);
            return List.of(mapper.mapRow(row, 0));
        });

        assertThat(lookup.require(tokens.encode(delivered), viewer, delivered.itemId(), now)).isEqualTo(delivered);
        verify(jdbc, times(1)).query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<MusicianFeedDeliveredItem>>any());
    }

    @Test
    void rejectsCrossAccountAndExpiredProofBeforeReadingTheLedger() {
        String token = tokens.encode(delivered);
        invalid(() -> lookup.require(token, UUID.randomUUID(), delivered.itemId(), now));
        invalid(() -> lookup.require(token, viewer, delivered.itemId(), delivered.expiresAt()));
        invalid(() -> lookup.require(token + "tampered", viewer, delivered.itemId(), now));
        verifyNoInteractions(jdbc);
    }

    @Test
    void queuedObservationUsesProofValidityAtExposureWithoutExtendingFeedbackExpiry() {
        returns(List.of(delivered));
        String token = tokens.encode(delivered);
        Instant received = now.plusSeconds(7200);
        assertThat(lookup.requireForObservation(token, viewer, delivered.itemId(), now, received)).isEqualTo(delivered);
        invalid(() -> lookup.require(token, viewer, delivered.itemId(), received));
        invalid(() -> lookup.requireForObservation(token, viewer, delivered.itemId(), now, now.plusSeconds(86401)));
        invalid(() -> lookup.requireForObservation(token, viewer, delivered.itemId(), now.plusSeconds(121), now));
        invalid(() -> lookup.requireForObservation(token, UUID.randomUUID(), delivered.itemId(), now, received));
    }

    @Test
    void tokenWithoutAnUnexpiredLedgerRowAndWrongExpectedIdentityAreRejected() {
        returns(List.of());
        invalid(() -> lookup.require(tokens.encode(delivered), viewer, delivered.itemId(), now));
        returns(List.of(delivered));
        invalid(() -> lookup.require(tokens.encode(delivered), viewer, "ANNOUNCEMENT:" + UUID.randomUUID(), now));
        assertThat(lookup.require(tokens.encode(delivered), viewer, null, now)).isEqualTo(delivered);
    }

    @Test
    void existingSignatureCannotAuthorizeChangedLedgerClaims() {
        for (String field : List.of("session", "item", "targetType", "targetId", "schema", "algorithm", "position")) {
            MusicianFeedDeliveredItem changed = new MusicianFeedDeliveredItem(
                    delivered.deliveryId(), viewer, field.equals("session") ? UUID.randomUUID() : session,
                    field.equals("item") ? "ANNOUNCEMENT:" + UUID.randomUUID() : delivered.itemId(),
                    delivered.itemType(), field.equals("targetType") ? "MEDIA" : delivered.targetType(),
                    field.equals("targetId") ? UUID.randomUUID() : target,
                    null, null, delivered.reasonCode(), delivered.feedbackCapabilities(),
                    field.equals("schema") ? 2 : delivered.schemaVersion(),
                    field.equals("algorithm") ? "other" : delivered.algorithmVersion(),
                    field.equals("position") ? 4 : delivered.absolutePosition(), null, delivered.evidenceJson(),
                    delivered.deliveredAt(), delivered.expiresAt(), delivered.purgeAfter(), delivered.lane());
            returns(List.of(changed));
            invalid(() -> lookup.require(tokens.encode(delivered), viewer, null, now));
        }
    }

    @Test
    void deliveryFacadeDelegatesToTheSharedLookupWithoutSourceResolution() {
        MusicianFeedDeliveryLookup proof = mock(MusicianFeedDeliveryLookup.class);
        MusicianFeedReplayVisibilityGuard visibility = mock(MusicianFeedReplayVisibilityGuard.class);
        MusicianFeedDeliveryService facade = new MusicianFeedDeliveryService(jdbc, tokens, properties,
                new ObjectMapper(), visibility, proof);
        when(proof.require("proof", viewer, delivered.itemId(), now)).thenReturn(delivered);
        assertThat(facade.require("proof", viewer, delivered.itemId(), now)).isSameAs(delivered);
        verify(proof).require("proof", viewer, delivered.itemId(), now);
        verifyNoInteractions(jdbc, visibility);
    }

    private void returns(List<MusicianFeedDeliveredItem> rows) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<MusicianFeedDeliveredItem>>any())).thenReturn(rows);
    }

    private ResultSet storedRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(delivered.deliveryId());
        when(row.getObject("viewer_user_id", UUID.class)).thenReturn(viewer);
        when(row.getObject("feed_session_id", UUID.class)).thenReturn(session);
        when(row.getString("item_id")).thenReturn(delivered.itemId());
        when(row.getString("item_type")).thenReturn(delivered.itemType().name());
        when(row.getString("target_type")).thenReturn(delivered.targetType());
        when(row.getObject("target_id", UUID.class)).thenReturn(target);
        when(row.getString("reason_code")).thenReturn(delivered.reasonCode());
        when(row.getString("feedback_capabilities")).thenReturn("HIDE");
        when(row.getInt("schema_version")).thenReturn(delivered.schemaVersion());
        when(row.getString("algorithm_version")).thenReturn(delivered.algorithmVersion());
        when(row.getLong("absolute_position")).thenReturn(delivered.absolutePosition());
        when(row.getString("evidence_json")).thenReturn(delivered.evidenceJson());
        when(row.getTimestamp("delivered_at")).thenReturn(Timestamp.from(delivered.deliveredAt()));
        when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(delivered.expiresAt()));
        when(row.getTimestamp("purge_after")).thenReturn(Timestamp.from(delivered.purgeAfter()));
        when(row.getString("feed_lane")).thenReturn(delivered.lane().name());
        return row;
    }

    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }

    private static MusicianFeedProperties properties() {
        MusicianFeedProperties value = new MusicianFeedProperties();
        value.setDeliverySecret("shared-delivery-lookup-test-secret-at-least-32-bytes");
        return value;
    }
}
