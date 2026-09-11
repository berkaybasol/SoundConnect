package com.berkayb.soundconnect.tools.simulation.seed.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

/**
 * Local-only public-media origin. Possession of the opaque 256-bit capability
 * is the authority; logical storage keys are never accepted over HTTP.
 */
@RestController
@Profile("local & simulation")
@ConditionalOnProperty(
		prefix = "app.simulation",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = false
)
@RequestMapping(SimulationFileStorageClient.PUBLIC_ROUTE)
public final class SimulationMediaReadController {

	private static final String NOSNIFF = "X-Content-Type-Options";

	private final SimulationFileStorageClient storageClient;

	public SimulationMediaReadController(SimulationFileStorageClient storageClient) {
		this.storageClient = storageClient;
	}

	@GetMapping("/{capability}")
	public ResponseEntity<byte[]> read(
			@PathVariable String capability,
			@RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader,
			@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
	) {
		SimulationFileStorageClient.PublicObject object = storageClient
				.readPublicCapability(capability)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		HttpHeaders headers = headers(object);
		if (etagMatches(ifNoneMatch, object.eTag())) {
			return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
		}

		byte[] bytes = object.bytes();
		if (rangeHeader == null || rangeHeader.isBlank()) {
			headers.setContentLength(bytes.length);
			return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
		}

		HttpRange range = singleRange(rangeHeader, bytes.length);
		long start;
		long end;
		try {
			start = range.getRangeStart(bytes.length);
			end = range.getRangeEnd(bytes.length);
		} catch (IllegalArgumentException invalidRange) {
			throw rangeNotSatisfiable(bytes.length);
		}
		if (start < 0 || end < start || end >= bytes.length) {
			throw rangeNotSatisfiable(bytes.length);
		}
		byte[] slice = Arrays.copyOfRange(bytes, Math.toIntExact(start), Math.toIntExact(end + 1));
		headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + bytes.length);
		headers.setContentLength(slice.length);
		return new ResponseEntity<>(slice, headers, HttpStatus.PARTIAL_CONTENT);
	}

	private static HttpHeaders headers(SimulationFileStorageClient.PublicObject object) {
		HttpHeaders headers = new HttpHeaders();
		try {
			headers.setContentType(MediaType.parseMediaType(object.contentType()));
		} catch (IllegalArgumentException invalidMetadata) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		headers.setCacheControl(object.cacheControl());
		headers.setETag(object.eTag());
		headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
		headers.set(NOSNIFF, "nosniff");
		return headers;
	}

	private static HttpRange singleRange(String rangeHeader, int objectSize) {
		try {
			List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
			if (ranges.size() != 1) throw rangeNotSatisfiable(objectSize);
			return ranges.getFirst();
		} catch (IllegalArgumentException invalidRange) {
			throw rangeNotSatisfiable(objectSize);
		}
	}

	private static boolean etagMatches(String requestHeader, String currentETag) {
		if (requestHeader == null || requestHeader.isBlank()) return false;
		for (String candidate : requestHeader.split(",")) {
			String normalized = candidate.trim();
			if ("*".equals(normalized)) return true;
			if (normalized.startsWith("W/")) normalized = normalized.substring(2).trim();
			if (currentETag.equals(normalized)) return true;
		}
		return false;
	}

	private static ResponseStatusException rangeNotSatisfiable(long objectSize) {
		return new ResponseStatusException(
				HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
				"Requested range is outside the simulation media object (size=" + objectSize + ")");
	}
}
