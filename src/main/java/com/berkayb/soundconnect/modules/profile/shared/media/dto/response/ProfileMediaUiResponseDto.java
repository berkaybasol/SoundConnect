package com.berkayb.soundconnect.modules.profile.shared.media.dto.response;

import com.berkayb.soundconnect.modules.media.dto.response.MediaResponseDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;

import java.util.List;


/**
 * Profile ekraninda medya alani icin frontende gonderilecek birlesik response.
 * Bu dto sadece UI icindir!
 */
public record ProfileMediaUiResponseDto(
		MediaResponseDto featuredVideo,
		List<MediaResponseDto> videos,
		List<TrackResponseDto> audios
) {
	public ProfileMediaUiResponseDto {
		videos = videos == null ? List.of() : videos;
		audios = audios == null ? List.of() : audios;
	}
}