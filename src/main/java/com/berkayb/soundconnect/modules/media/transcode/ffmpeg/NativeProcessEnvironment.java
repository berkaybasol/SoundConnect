package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Removes application credentials from FFmpeg/ffprobe child environments. */
public final class NativeProcessEnvironment {
	private static final Set<String> ALLOWED = Set.of(
			"path", "pathext", "systemroot", "windir",
			"temp", "tmp", "tmpdir", "lang", "lc_all", "tz"
	);

	private NativeProcessEnvironment() {
	}

	public static void sanitize(ProcessBuilder builder) {
		Map<String, String> environment = builder.environment();
		Map<String, String> retained = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : environment.entrySet()) {
			if (ALLOWED.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
				retained.put(entry.getKey(), entry.getValue());
			}
		}
		environment.clear();
		environment.putAll(retained);
	}
}
