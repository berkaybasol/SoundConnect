package com.berkayb.soundconnect.modules.media.enums;

/**
 *
 * ----------------------------------Terimlere Takilirsan MediaModule.md bak--------------------------------------------
 *
 * Medya dosyasinin temel turunu ifade eden enum.
 * sistemdeki is akislarini MIME dogrulamasini ve oynatma protokolunu belirler
 * - IMAGE: gorsel dosyalar (jpg,png,webp)
 * - AUDIO: Ses dosyalari (mp3/wav/aac/ogg)
 * - VIDEO: video dosyalari (mp4/mov/mkv)
 * PDF, GIF gibi yeni turler eklenirse burada tanimlanir ve policy guncellenir
 */
public enum MediaKind {
	IMAGE,
	AUDIO,
	VIDEO,
}