package com.berkayb.soundconnect.modules.tablegroup.game.support;

import com.berkayb.soundconnect.shared.util.UsernameUtils;

/** Produces a single safe chat-card mention without changing stored usernames. */
public final class TableGroupGameMentionFormatter {

	private TableGroupGameMentionFormatter() {
	}

	public static String mention(String username) {
		String sanitized = username == null ? "" : username
				.replace('\r', ' ')
				.replace('\n', ' ')
				.replace('\u0085', ' ')
				.replace('\u2028', ' ')
				.replace('\u2029', ' ');
		sanitized = UsernameUtils.stripBoundaryWhitespace(sanitized);
		int contentStart = 0;
		while (contentStart < sanitized.length() && sanitized.charAt(contentStart) == '@') {
			contentStart++;
		}
		return "@" + sanitized.substring(contentStart);
	}
}
