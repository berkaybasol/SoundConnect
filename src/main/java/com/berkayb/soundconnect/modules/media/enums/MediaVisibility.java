package com.berkayb.soundconnect.modules.media.enums;


/**
 * Medyanin gorunurluk durumunu belirten sinif
 *
 */
public enum MediaVisibility {
	PUBLIC,
	// Not publicly listable. Until revocable share tokens exist, access is owner-only.
	UNLISTED,
	PRIVATE;
	
	
	
	// public listelerde gosterilebilir mi?
	public boolean isPubliclyListable(){
		return this == PUBLIC;
	}
}
