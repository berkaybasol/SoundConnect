package com.berkayb.soundconnect.modules.media.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class MediaContentSignatureValidator {

	private static final int HEADER_BYTES = 16;

	private MediaContentSignatureValidator() {
	}

	public static boolean matches(String mimeType, InputStream input) throws IOException {
		if (input == null) {
			return false;
		}
		byte[] header = input.readNBytes(HEADER_BYTES);
		String mime = MediaMimeType.sanitize(mimeType);
		return switch (mime) {
			case "image/png" -> startsWith(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
			case "image/jpeg" -> startsWith(header, 0xFF, 0xD8, 0xFF);
			case "image/webp" -> asciiAt(header, 0, "RIFF") && asciiAt(header, 8, "WEBP");
			case "audio/mpeg" -> asciiAt(header, 0, "ID3") || hasMpegFrameSync(header);
			case "audio/mp4", "audio/x-m4a", "video/mp4", "video/quicktime" ->
					asciiAt(header, 4, "ftyp");
			case "audio/aac" -> hasMpegFrameSync(header);
			case "audio/flac" -> asciiAt(header, 0, "fLaC");
			case "audio/wav", "audio/x-wav" ->
					asciiAt(header, 0, "RIFF") && asciiAt(header, 8, "WAVE");
			case "audio/ogg" -> asciiAt(header, 0, "OggS");
			case "video/x-matroska" -> startsWith(header, 0x1A, 0x45, 0xDF, 0xA3);
			default -> false;
		};
	}

	private static boolean startsWith(byte[] input, int... signature) {
		if (input.length < signature.length) {
			return false;
		}
		for (int index = 0; index < signature.length; index++) {
			if (Byte.toUnsignedInt(input[index]) != signature[index]) {
				return false;
			}
		}
		return true;
	}

	private static boolean asciiAt(byte[] input, int offset, String expected) {
		byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
		return input.length >= offset + expectedBytes.length
				&& Arrays.equals(
						Arrays.copyOfRange(input, offset, offset + expectedBytes.length),
						expectedBytes
				);
	}

	private static boolean hasMpegFrameSync(byte[] header) {
		return header.length >= 2
				&& Byte.toUnsignedInt(header[0]) == 0xFF
				&& (Byte.toUnsignedInt(header[1]) & 0xE0) == 0xE0;
	}
}
