package com.berkayb.soundconnect.modules.media.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface StorageClient {
	
	// client buraya yukler
	String createPresignedPutUrl(String objectKey, String mimeType);
	
	/**
	 *
	 * @param local Yuklenecek yerel dosya (orn:.../master/m3u8
	 * @param key  Object key (Orn: media/{assetId}/hls/master.m3u8)
	 * @param contentType HTTP Content - Type (orn: application/vnd.apple.mpegurl)
	 * @param cacheControl Cache-Control basligi (orn: public, max-age=30)
	 */
	void putFile(Path local, String key, String contentType, String cacheControl);
	
	// bellekteki veriyi (kucuk dosyalar/manifest) S3'e yukler.
	void putBytes(byte[] data, String key, String contentType, String cacheControl);
	
	// object'i stream olarak ac (buyuk dosyalarda stream ederek yazmak icin)
	// kullanan taraf akisi kapatmakla yukumludur
	InputStream getObjectStream(String key);
	
	// public erisim URL'si (CDN ustunden)
	String publicUrl(String objectKey);
	
	// dosya sil
	void deleteObject(String objectKey);
	
	// Objetc'i dogrudan hedef dosyaya indir
	void downloadToFile(String key, Path target);
	
	// prefix altindaki dosyalari sil(orn. HLS klasoru)
	void deleteFolder(String prefix);
}