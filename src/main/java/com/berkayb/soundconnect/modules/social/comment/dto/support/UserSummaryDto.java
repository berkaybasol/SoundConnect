package com.berkayb.soundconnect.modules.social.comment.dto.support;

import java.util.UUID;

// yorum sahibinin minimal bilgisi flutterda commentlerde ve replylerde gorulecek alan
public record UserSummaryDto(
		UUID id,
		String username,
		String avatarUrl
) {
}