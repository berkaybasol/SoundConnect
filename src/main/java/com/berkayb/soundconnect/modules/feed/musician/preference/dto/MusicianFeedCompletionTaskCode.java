package com.berkayb.soundconnect.modules.feed.musician.preference.dto;

/** Stable machine codes. User-facing copy and navigation belong to the client. */
public enum MusicianFeedCompletionTaskCode {
	OPPORTUNITY_CITY,
	INSTRUMENTS,
	BIO,
	PORTFOLIO,
	PROFILE_PHOTO_AND_SOCIAL_LINKS,
	/**
	 * Legacy v1 response code retained so an in-flight/replayed payload can still
	 * be deserialized. Completion criteria v2 never emits it.
	 */
	@Deprecated
	STAGE_NAME_AND_BIO
}
