package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SimulationMediaReadControllerTest {

	@TempDir
	Path temporaryDirectory;

	private SimulationFileStorageClient storage;
	private MockMvc mockMvc;
	private byte[] bytes;
	private String route;
	private String eTag;

	@BeforeEach
	void setUp() {
		storage = new SimulationFileStorageClient(
				mock(SimulationRuntimeGuard.class),
				temporaryDirectory.resolve("reports"),
				Clock.systemUTC(),
				Duration.ofMinutes(15),
				Duration.ofMinutes(5));
		bytes = new SimulationMediaFixtures().audio().bytes();
		String key = "media/controller/source.wav";
		storage.putBytes(bytes, key, "audio/wav", "public, max-age=60");
		eTag = storage.getObjectMetadata(key).orElseThrow().eTag();
		route = URI.create(storage.publicUrl(key)).getPath();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new SimulationMediaReadController(storage))
				.build();
	}

	@Test
	void servesBoundedPublicObjectWithSafeHeaders() throws Exception {
		byte[] response = mockMvc.perform(get(route))
				.andExpect(status().isOk())
				.andExpect(content().contentType("audio/wav"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=60"))
				.andExpect(header().string(HttpHeaders.ETAG, eTag))
				.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andReturn().getResponse().getContentAsByteArray();

		assertThat(response).isEqualTo(bytes);
	}

	@Test
	void supportsSingleRangeAndConditionalCacheValidation() throws Exception {
		byte[] partial = mockMvc.perform(get(route).header(HttpHeaders.RANGE, "bytes=4-11"))
				.andExpect(status().isPartialContent())
				.andExpect(header().string(
						HttpHeaders.CONTENT_RANGE, "bytes 4-11/" + bytes.length))
				.andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 8L))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(partial).isEqualTo(java.util.Arrays.copyOfRange(bytes, 4, 12));

		mockMvc.perform(get(route).header(HttpHeaders.IF_NONE_MATCH, "W/" + eTag))
				.andExpect(status().isNotModified())
				.andExpect(content().bytes(new byte[0]));
	}

	@Test
	void rejectsUnknownCapabilitiesAndMultipartRangesWithoutKeyOracle() throws Exception {
		mockMvc.perform(get(SimulationFileStorageClient.PUBLIC_ROUTE
				+ "/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
				.andExpect(status().isNotFound());

		mockMvc.perform(get(route).header(HttpHeaders.RANGE, "bytes=0-1,4-5"))
				.andExpect(status().isRequestedRangeNotSatisfiable());
		mockMvc.perform(get(route).header(HttpHeaders.RANGE, "bytes=999999-"))
				.andExpect(status().isRequestedRangeNotSatisfiable());
	}
}
