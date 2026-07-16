package com.berkayb.soundconnectworker.persistence;

import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;

/**
 * The sole Spring Data repository admitted to the native-worker context.
 *
 * <p>The production database role remains the authoritative capability fence:
 * it can select {@code tbl_media_asset} and update only lifecycle/derived-media
 * columns. It cannot insert/delete media rows or access user/auth/mail tables.</p>
 */
public interface MediaWorkerAssetRepository extends MediaAssetRepository {
}
