package com.berkayb.soundconnect.modules.media.enums;


/**
 * Bu enum medya dosyasinin yasam dongususnu temsil eder.
 *
 * Akis:
 * 1- UPLOADING: istemci presigned url ile dosyayi storage'a put ederken.
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
	 * sunucu tarafinda isleme asamasi:
	 * thumnail cikarma, meta hesaplama (duration/dimensions), transcode, virus taramasi vb.
	 */
	PROCESSING,
	
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