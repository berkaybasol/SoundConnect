package com.berkayb.soundconnect.shared.mail.adapter;

import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailSenderClientImplTest {
	
	private MockWebServer server;
	private MailSenderClientImpl client;
	private MailJobHelper helper;
	
	@BeforeEach
	void setUp() throws Exception {
		server = new MockWebServer();
		server.start();
		
		// Base URL: prod config /v1/email — aynısını taklit ediyoruz
		String baseUrl = server.url("/v1/email").toString();
		
		WebClient webClient = WebClient.builder()
		                               .baseUrl(baseUrl)
		                               .build();
		
		helper = Mockito.mock(MailJobHelper.class);
		client = new MailSenderClientImpl(helper, webClient);
		
		// @Value alanlarını setliyoruz
		setField(client, "fromEmail", "noreply@soundconnect.app");
		setField(client, "fromName", "SoundConnect");
		setField(client, "readTimeoutSec", 2);
	}
	
	@AfterEach
	void tearDown() throws Exception {
		server.shutdown();
	}
	
	private static void setField(Object target, String field, Object value) {
		try {
			Field f = target.getClass().getDeclaredField(field);
			f.setAccessible(true);
			f.set(target, value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	// ---------- TESTS ----------
	
	@Test
	@DisplayName("200/202 → başarılı gönderim")
	void send_success_2xx() {
		server.enqueue(new MockResponse()
				               .setResponseCode(202)
				               .setBody("{}")
				               .addHeader("Content-Type", "application/json"));
		
		client.send("alice@example.com", "Hello", "hi", "<b>hi</b>");
		
		assertThat(server.getRequestCount()).isEqualTo(1);
	}
	
	@Test
	@DisplayName("429 → HttpClientErrorException (Retry-After header’lı)")
	void send_429_clientError() {
		server.enqueue(new MockResponse()
				               .setResponseCode(429)
				               .setBody("{\"error\":\"rate limit\"}")
				               .addHeader("Retry-After", "7")
				               .addHeader("Content-Type", "application/json"));
		
		assertThatThrownBy(() ->
				                   client.send("bob@example.com", "Rate me", "t", "<b>h</b>")
		).isInstanceOf(HttpClientErrorException.class)
		 .satisfies(ex -> {
			 HttpClientErrorException e = (HttpClientErrorException) ex;
			 assertThat(e.getStatusCode().value()).isEqualTo(429);
		 });
	}
	
	@Test
	@DisplayName("503 → HttpServerErrorException")
	void send_503_serverError() {
		server.enqueue(new MockResponse()
				               .setResponseCode(503)
				               .setBody("{\"error\":\"down\"}")
				               .addHeader("Content-Type", "application/json"));
		
		assertThatThrownBy(() ->
				                   client.send("carol@example.com", "Down", "t", "<b>h</b>")
		).isInstanceOf(HttpServerErrorException.class)
		 .satisfies(ex -> {
			 HttpServerErrorException e = (HttpServerErrorException) ex;
			 assertThat(e.getStatusCode().value()).isEqualTo(503);
		 });
	}
	
	@Test
	@DisplayName("IO/Connect hatası → SoundConnectException (genel mesaj)")
	void send_io_error_resourceAccess() {
		WebClient broken = WebClient.builder()
		                            .baseUrl("http://localhost:1") // connection refused
		                            .build();
		
		MailSenderClientImpl ioClient = new MailSenderClientImpl(helper, broken);
		setField(ioClient, "fromEmail", "noreply@soundconnect.app");
		setField(ioClient, "fromName", "SoundConnect");
		setField(ioClient, "readTimeoutSec", 1);
		
		assertThatThrownBy(() ->
				                   ioClient.send("dave@example.com", "Hi", "t", "<b>h</b>")
		).isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class)
		 .hasMessageContaining("Mail could not be queued");
	}
	
	@Test
	@DisplayName("Validation: boş subject → IllegalArgumentException")
	void send_validation_subject() {
		assertThatThrownBy(() ->
				                   client.send("eve@example.com", "  ", "t", null)
		).isInstanceOf(IllegalArgumentException.class)
		 .hasMessageContaining("subject");
	}
	
	@Test
	@DisplayName("Validation: hem text hem html boş → IllegalArgumentException")
	void send_validation_bodies() {
		assertThatThrownBy(() ->
				                   client.send("frank@example.com", "S", "  ", "   ")
		).isInstanceOf(IllegalArgumentException.class)
		 .hasMessageContaining("either 'textBody' or 'htmlBody'");
	}
}