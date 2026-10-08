package com.berkayb.soundconnect.modules.marketplace.media;

import com.berkayb.soundconnect.modules.marketplace.support.MarketplaceAccess;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketplaceMediaAccessTest {
    @Mock MarketplaceAccess access;
    @Mock NamedParameterJdbcTemplate jdbc;
    @InjectMocks MarketplaceMediaAccess media;
    final UUID actor = UUID.randomUUID(), listing = UUID.randomUUID(), asset = UUID.randomUUID();

    @Test void uploadCapacityIsCheckedAfterAggregateLockAndBeforeReservation() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(UUID.class))).thenReturn(List.of(actor));
        when(jdbc.queryForObject(contains("count(*)"), anyMap(), eq(Long.class))).thenReturn(16L);
        assertThatThrownBy(() -> media.requireUploadOwner(actor, listing)).isInstanceOf(SoundConnectException.class);
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForList(contains("tbl_user"), anyMap(), eq(UUID.class));
        order.verify(jdbc).queryForList(contains("for update"), anyMap(), eq(UUID.class));
        order.verify(jdbc).queryForObject(contains("count(*)"), anyMap(), eq(Long.class));
    }

    @Test void listenerCannotUseModerationToReadAnAsset() {
        when(jdbc.queryForList(contains("tbl_user"), anyMap(), eq(UUID.class))).thenReturn(List.of(actor));
        doThrow(new SoundConnectException(ErrorType.MARKETPLACE_FORBIDDEN)).when(access).requireModerator(actor);
        doThrow(new SoundConnectException(ErrorType.MARKETPLACE_FORBIDDEN)).when(access).requireBackstage(actor);
        assertThatThrownBy(() -> media.requireMediaAccess(actor, listing, asset)).isInstanceOf(SoundConnectException.class);
        verify(jdbc, never()).queryForObject(anyString(), anyMap(), eq(Boolean.class));
    }

    @Test void moderatorCanReadRetainedEvidenceAfterListingWasDeleted() {
        when(jdbc.queryForList(contains("tbl_user"), anyMap(), eq(UUID.class))).thenReturn(List.of(actor));
        when(jdbc.queryForObject(contains("tbl_marketplace_report_photo"), anyMap(), eq(Boolean.class))).thenReturn(true);
        media.requireMediaAccess(actor, listing, asset);
        verify(access).requireModerator(actor);
        verify(access, never()).requireBackstage(actor);
        verify(jdbc, never()).queryForList(contains("select owner_user_id"), anyMap(), eq(UUID.class));
    }

    @Test void ordinaryBuyerCannotReadUnattachedImageFromPublishedListing() {
        when(jdbc.queryForList(contains("tbl_user"), anyMap(), eq(UUID.class))).thenReturn(List.of(actor));
        doThrow(new SoundConnectException(ErrorType.MARKETPLACE_FORBIDDEN)).when(access).requireModerator(actor);
        when(jdbc.queryForList(contains("select owner_user_id"), anyMap(), eq(UUID.class))).thenReturn(List.of(UUID.randomUUID()));
        when(jdbc.queryForObject(contains("p.media_asset_id=:asset"), anyMap(), eq(Boolean.class))).thenReturn(false);
        assertThatThrownBy(() -> media.requireMediaAccess(actor, listing, asset)).isInstanceOf(SoundConnectException.class);
    }

    @Test void ownerCanPreviewAnUnattachedPhotoWhileEditing() {
        when(jdbc.queryForList(contains("tbl_user"), anyMap(), eq(UUID.class))).thenReturn(List.of(actor));
        doThrow(new SoundConnectException(ErrorType.MARKETPLACE_FORBIDDEN)).when(access).requireModerator(actor);
        when(jdbc.queryForList(contains("select owner_user_id"), anyMap(), eq(UUID.class))).thenReturn(List.of(actor));
        media.requireMediaAccess(actor, listing, asset);
        verify(jdbc, never()).queryForObject(anyString(), anyMap(), eq(Boolean.class));
    }
}
