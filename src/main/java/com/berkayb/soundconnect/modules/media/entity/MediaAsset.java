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
	
	
	@Column(columnDefinition = "uuid")
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
	
}