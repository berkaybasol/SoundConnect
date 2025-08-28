package com.berkayb.soundconnect.modules.media.support;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class MediaAssetSupport {
	
	private MediaAssetSupport() {
		// util class o yuzden instance yok
	}
	
	// public listelerde gosterilebilir mi?
	public static boolean isPubliclyListable(MediaAsset asset) {
		return asset != null && asset.getStatus() == MediaStatus.READY
			&& asset.getVisibility() == MediaVisibility.PUBLIC;
	}
	
	// gorsel mi?
	public static boolean isImage(MediaAsset asset) {
		return hasKind(asset, MediaKind.IMAGE);
		}
		
	// ses mi?
	public static boolean isAudio(MediaAsset asset) {
		return hasKind(asset,MediaKind.AUDIO);
	}
	
	// video mu?
	public static boolean isVideo(MediaAsset asset) {
		return hasKind(asset, MediaKind.VIDEO);
	}
	
	// null guvenli kind karsilastirmasi
	public static boolean hasKind(MediaAsset asset, MediaKind kind) {
		return asset != null && asset.getKind() == kind;
	}
	
	// oynatilabilir mi?
	public static boolean canServePlayback(MediaAsset asset) {
		return asset != null && asset.getStatus() == MediaStatus.READY;
	}
	
	// thumnail url var mi? sunulailir mi?
	public static boolean hasDisplayableThumbnail(MediaAsset asset) {
		return canServePlayback(asset) && notBlank(asset.getThumbnailUrl());
	}
	
	// kaynak url mevcut mu?
	public static boolean hasSource(MediaAsset asset) {
		return asset != null && notBlank(asset.getSourceUrl());
	}
	
	// video url mevcut mu?
	public static boolean hasPlayback(MediaAsset asset) {
		return asset != null && notBlank(asset.getPlaybackUrl());
	}
	
	
	private static boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}
	
	private static String stripTrailingSlash(String s) {
		if (!StringUtils.hasText(s)) return "";
		return s.replaceAll("/+$", "");
	}
		
	}