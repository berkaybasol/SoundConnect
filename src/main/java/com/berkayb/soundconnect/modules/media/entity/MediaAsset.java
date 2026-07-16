package com.berkayb.soundconnect.modules.media.entity;

import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.Length;

import java.time.LocalDateTime;
import java.util.UUID;


/**
 * S3 veya R2 gibi obje depolamaya yukledigimiz her turlu medya varligini (image,audio,video)
 tek bir tabloda yonetmek amacli entity sinifi.
 * sahiplik (owner), gorunurluk(visibility), durum(status) ve playback/thumnail url'leri gibi
 operasyonel bilgileri tek yerde tutuyoruz
 */

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "tbl_media_asset",
		indexes = {
				@Index(name = "idx_media_owner", columnList = "ownerType,ownerId"),
				@Index(name = "idx_media_kind", columnList = "kind"),
				@Index(name = "idx_media_status", columnList = "status"),
				@Index(name = "idx_media_status_created", columnList = "status,createdAt"),
				@Index(name = "idx_media_status_updated", columnList = "status,updatedAt"),
				@Index(name = "idx_media_verification_lease", columnList = "status,uploadVerificationLeaseExpiresAt"),
				@Index(name = "idx_media_verification_cleanup", columnList = "uploadVerificationCleanupNotBefore"),
				@Index(name = "idx_media_transcode_lease", columnList = "status,transcodeLeaseUntil"),
				@Index(name = "idx_media_visibility", columnList = "visibility")
		}
)
public class MediaAsset extends BaseEntity {
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private MediaKind kind; // medya turu image,audio,video
	
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	@Builder.Default
	private MediaStatus status = MediaStatus.UPLOADING; // medya yasam dongusu uploading, processing, ready, failed
	
	
	@Enumerated(EnumType.STRING)
	@Builder.Default
	@Column(nullable = false, length = 16)
	private MediaVisibility visibility = MediaVisibility.PUBLIC; // medyanin gorunurlugu public, unlisted(only link), private
	
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private MediaOwnerType ownerType; // medya kime ait? user(butun profiller), band, venue
	
	
	@Column(columnDefinition = "uuid", nullable = false)
	private UUID ownerId;
	
	/**
	 * obje depolamadaki (S3/R2) anahtar/konum bilgisi
	 * orn: media/{assetId}/source.mp4
	 * sadece sistem ici takipte kullanilir. client gormez.
	 */
	@Column(length = 512)
	private String storageKey;
	
	
	/**
	 * orjinal dosyaya(veya cdn ustunden ayni icerige) erisim url'si
	 * image genellikle direkt gosterilir.
	 * biz videoda hls kullanicaz image ve audio da ise proggresiveplayback
	 */
	@Column(length = 1024)
	private String sourceUrl;
	
	
	@Column(length = 1024)
	private String playbackUrl; // video icin HLS manifest (m3u8) auidio icin normalize stream.
								// MVP icin sourceUrl ile ayni olabilir. ileride farklilascak
	
	@Column(length = 1024)
	private String thumbnailUrl;

	/**
	 * Stable timestamp for asynchronous deletion fencing. Unlike {@code updatedAt},
	 * this value is not moved by storage retry backoff, so derivative producers
	 * that were already running when deletion was requested receive one bounded
	 * grace window and failed cleanup retries do not restart that window.
	 */
	private LocalDateTime deletionRequestedAt;

	/** Exact per-upload UTC deadline captured after the presigned URL is minted. */
	private LocalDateTime uploadWriteAuthorityExpiresAt;

	/**
	 * Exact UTC physical-delete fence captured with the deletion transaction.
	 * Persisting it prevents a later configuration change from shortening the
	 * safety window of work that was already in flight.
	 */
	private LocalDateTime physicalDeletionNotBefore;

	/**
	 * Fences exactly one upload verification attempt across every application
	 * instance. A worker may finalize or reject the row only while this token
	 * still matches the token it claimed.
	 */
	@Column(columnDefinition = "uuid")
	private UUID uploadVerificationAttemptToken;

	/** UTC lease after which crash recovery may claim a new verification token. */
	private LocalDateTime uploadVerificationLeaseExpiresAt;

	/** Non-renewable UTC upper bound for every write by the current attempt. */
	private LocalDateTime uploadVerificationAttemptDeadline;

	/**
	 * Monotonic UTC fence after which orphan attempt prefixes may be swept. A
	 * reclaim extends this value but can never shorten an earlier producer tail.
	 */
	private LocalDateTime uploadVerificationCleanupNotBefore;

	/**
	 * Fences one concrete HLS worker attempt. Rabbit deliveries are at-least-once,
	 * therefore status alone cannot distinguish the live worker from a late worker
	 * that resumed after crash recovery reassigned the asset.
	 */
	@Column(columnDefinition = "uuid")
	private UUID transcodeAttemptToken;

	/** UTC deadline renewed by the live HLS worker heartbeat. */
	private LocalDateTime transcodeLeaseUntil;

	/**
	 * Non-renewable upper bound for all writes by this attempt. Crash recovery may
	 * detect a dead worker through the short lease, but it must not reuse the
	 * deterministic HLS prefix before this hard deadline.
	 */
	private LocalDateTime transcodeAttemptDeadline;

	/** Durable fence used by cleanup scheduling after an expired lease. */
	private LocalDateTime transcodeCleanupNotBefore;

	/** Monotonic, durable attempt budget. It is deliberately not reset on retry. */
	@Builder.Default
	@Column(nullable = false)
	private int transcodeAttemptCount = 0;

	/**
	 * Distinguishes terminal HLS cleanup from crash-recovery cleanup. Retry cleanup
	 * removes only the deterministic derivative prefix and preserves the verified
	 * source for the next bounded attempt.
	 */
	@Builder.Default
	@Column(nullable = false)
	private boolean transcodeRetryPending = false;

	/**
	 * Exhausted infrastructure retries clean public derivatives immediately but
	 * retain the verified source for a bounded operator/manual-replay window.
	 */
	@Builder.Default
	@Column(nullable = false)
	private boolean transcodeRetainSourceAfterCleanup = false;
	
	
	/**
	 * dosyanin formatini tanimlar. media kindden farkli olarak bu alan kullaniciya degil sistemin teknik isleyisine
	 * yoneliktir.
	 * istemci davranisini ve guvenlik filtrelerini belirler
	 */
	@Column(nullable = false, length = 64)
	private String mimeType;
	
	@Column(nullable = false)
	private Long size;
	
	private Integer durationSeconds; // auidio video icin sure
	
	private Integer width; // video icin genislik
	
	private Integer height; // video icin uzunluk boyu
	
	@Column(length = 128)
	private String title;
	
	@Column(length =  512)
	private String description;
	
	
	/**
	 * Video icin HLS (manifest .m3u8)
	 * IMAGE/AUIDIO icin PROGRESSIVE
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	@Builder.Default
	private MediaStreamingProtocol streamingProtocol = MediaStreamingProtocol.PROGRESSIVE;

	/**
	 * Defense in depth: protected media is addressed only by storageKey and
	 * owner-authorized, short-lived origin signatures. Stable delivery URLs must
	 * never be written even if a future service path forgets the visibility rule.
	 */
	@PrePersist
	@PreUpdate
	private void enforceProtectedUrlInvariant() {
		if (visibility != null && visibility != MediaVisibility.PUBLIC) {
			sourceUrl = null;
			playbackUrl = null;
			thumbnailUrl = null;
		}
	}
	
}
