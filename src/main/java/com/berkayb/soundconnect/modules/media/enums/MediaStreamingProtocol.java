package com.berkayb.soundconnect.modules.media.enums;

/**
 * Oynatma protokulunu belirten enum sinifi.
 * PROGGRESIVE: tek dosya stream(range ile) basit, transcode sart degil.
 * HLS : HTTP Live Streaming (m3u8 manifest + segmentler). adaptif bitrate
 *
 * Not:
 * Video icin HLS kullanacagiz IMAGE/AUIDIO icin PROGGRESIVE kullanacagiz.
 */
public enum MediaStreamingProtocol {
	PROGRESSIVE,
	HLS
}