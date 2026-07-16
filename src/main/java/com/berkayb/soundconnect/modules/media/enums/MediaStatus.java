package com.berkayb.soundconnect.modules.media.enums;


/**
 * MediaStatus - bir medya dosyasinin sistemdeki yasam dongusunu temsil eder.
 *
 * Akis:
 * 1- UPLOADING: istemci presigned url ile dosyayi storage'a put ederken. (yukleme baslatildi ama henuz tamamlanmadi)
 * 2- PROCESSING: Sunucu tarafi islem/indeks/thumnail/trasncode asamasi.
 * 3- READY: Yayina hazir. URL'ler (playpack/thumbnail) son kullaniciya servis edilebilir.
 * 4- FAILED: Yukleme ya da isletme kirildi. kullaniciya yeniden deneme/teshis mesaji verilir/
 *
 * Notlar:
 * UPLOADING -> PROCESSING -> READY temel dogru yol
 * UPLOADING -> PROCESSING -> FAILED hatali senaryolar
 * READY ve FAILED terminal yani son durumlardir.
 * Servis'de bu enuma gore izinler/verilcek yanitlar duzenlir.
 * READY olmayan icerikler public listelerde gosterilmez
 * FAILED icerikler temizlenebilir veya yeniden denenebilir.
  */

public enum MediaStatus {
	/**
	 * Dosya istemci tarafindan storage'a yuklenir(PUT/Multipart vs.)
	 * bu asamada kullanici yukleme ilerlemesi gorur.
	 * sunucu tipik olarak yalnizca "yukleme oturumu" bilgisini tutar.
	 */
	UPLOADING,

	/**
	 * The completion request has durably fenced the mutable upload and queued
	 * post-commit object verification. Storage HEAD/COPY/GET/promotion work is
	 * performed only outside database transactions; a scheduled dispatcher
	 * retries this state after process death or transient object-store failure.
	 */
	VERIFYING,

	/**
	 * An abandoned upload has been atomically claimed for object cleanup. The
	 * cleanup worker retries while this state remains, so a transient storage
	 * failure cannot orphan an untracked object.
	 */
	CLEANUP_PENDING,

	/**
	 * An authenticated delete has committed and object cleanup is pending.
	 * This row is the durable, retryable deletion intent; storage I/O must never
	 * happen in the transaction that enters this state.
	 */
	DELETION_PENDING,

	/**
	 * The upload has been verified and durably committed for asynchronous
	 * transcoding. The database row is the dispatch intent; a background
	 * dispatcher keeps publishing while the asset remains in this state.
	 */
	TRANSCODE_QUEUED,

	/**
	 * RabbitMQ confirmed the durable message. The consumer may atomically claim
	 * either QUEUED (publish/consume race) or SENT; this state prevents recovery
	 * polling from flooding the broker while workers are temporarily offline.
	 */
	TRANSCODE_SENT,
	
	/**
	 * sunucu tarafinda isleme asamasi:
	 * thumnail cikarma, meta hesaplama (duration/dimensions), transcode, virus taramasi vb.
	 */
	PROCESSING,

	/**
	 * A video transcode did not finalize and its deterministic public HLS prefix
	 * must be removed before the asset becomes terminal. Keeping this as a
	 * durable state prevents process crashes or transient S3 failures from
	 * leaving publicly addressable orphan segments.
	 */
	HLS_CLEANUP,
	
	/**
	 * icerik yayinlanabilir durumda
	 * public listelerde gorunebilecek tek durum budur.
	 */
	READY,
	
	/**
	 * yukleme yada isleme basarasiz oldu
	 * hata loglanir, kullaniciya tekrar deneme imkani verilebilir
	 * arka planda temizlik/garbage collector surecleri tetiklenebilir.
	 */
	FAILED;
	
	/**
	 * Bu durum terminal mi? (READY veya FAILED)
	 * servis katmani akis kontrolunde pratik yardimci metod.
	 */
	public boolean isTerminal() {
		return this == MediaStatus.READY || this == MediaStatus.FAILED;
	}
}
