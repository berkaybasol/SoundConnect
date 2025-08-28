package com.berkayb.soundconnect.modules.media.enums;


/**
 * Medyanin gorunurluk durumunu belirten sinif
 *
 */
public enum MediaVisibility {
	PUBLIC,
	UNLISTED, // aramalarda cikmaz yalnizca link yoluyla erisim.
	PRIVATE;
	
	
	
	// public listelerde gosterilebilir mi?
	public boolean isPubliclyListable(){
		return this == PUBLIC;
	}
}