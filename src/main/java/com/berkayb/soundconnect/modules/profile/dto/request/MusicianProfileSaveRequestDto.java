package com.berkayb.soundconnect.modules.profile.dto.request;

import java.util.Set;
import java.util.UUID;

// burada user id gondermiyoruz cunku zaten oturum acmis kullanicinin profili olacak.

public record MusicianProfileSaveRequestDto(
		String stageName,
		String bio,
		String profilePicture,
		String instagramUrl,
		String youtubeUrl,
		String soundcloudUrl,
		String spotifyEmbedUrl,
		Set<UUID> instrumentIds
		
) {
}