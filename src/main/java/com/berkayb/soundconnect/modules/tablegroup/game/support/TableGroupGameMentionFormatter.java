package com.berkayb.soundconnect.modules.tablegroup.game.support;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameOutcome;
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

	/**
	 * Builds the server-owned completion copy from structured identity data.
	 * Keeping this next to mention sanitization lets read projections safely
	 * regenerate a stored result when a selected listener is currently Ghost.
	 */
	public static String resultMessage(TableGroupGameOutcome outcome, String username) {
		String selectedMention = mention(username);
		return switch (outcome) {
			case VOLUNTEER -> "SoundConnect ve masan, sadakatini takdir ediyor! "
					+ selectedMention + " hesabı gönüllü olarak üstlendi. 😎";
			case ASSIGNED -> "Geçmiş olsun " + selectedMention
					+ "! Masan tarafından hesabı ödemekle cezalandırıldın. "
					+ "Umarız ipin ucu çok kaçmamıştır. 😄";
		};
	}
}
