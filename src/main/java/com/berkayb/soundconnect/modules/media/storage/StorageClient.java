package com.berkayb.soundconnect.modules.media.storage;

public interface StorageClient {
	
	// client buraya yukler
	String createPresignedPutUrl(String objectKey, String mimeType);
	
	// public erisim URL'si (CDN ustunden)
	String publicUrl(String objectKey);
	
	// dosya sil
	void deleteObject(String objectKey);
	
	// prefix altindaki dosyalari sil(orn. HLS klasoru)
	void deleteFolder(String prefix);
}