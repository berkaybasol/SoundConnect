package com.berkayb.soundconnect.shared.mail.helper;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MailJobHelperTest {
	
	// Redis'e ihtiyaç duymayan metodları test ediyoruz; ctor için dummy veriyoruz.
	private final StringRedisTemplate redis = null; // kullanılmıyor
	private final MailJobHelper helper = new MailJobHelper(redis);
	
	@Test
	@DisplayName("maskEmail: temel maskeleme")
	void maskEmail_basic() {
		assertThat(helper.maskEmail("alice@example.com"))
				.startsWith("a***@e***.")
				.endsWith(".com");
		assertThat(helper.maskEmail(null)).isEqualTo("null");
		assertThat(helper.maskEmail("a@b")).isEqualTo("***");
	}
	
	@Test
	@DisplayName("stableParamsString: anahtar sıralı ve deterministik")
	void stableParamsString_deterministic() {
		Map<String, Object> p1 = new HashMap<>();
		p1.put("z", 3);
		p1.put("a", 1);
		p1.put("m", "two");
		
		Map<String, Object> p2 = new LinkedHashMap<>();
		p2.put("m", "two");
		p2.put("z", 3);
		p2.put("a", 1);
		
		String s1 = helper.stableParamsString(p1);
		String s2 = helper.stableParamsString(p2);
		
		// Aynı set -> aynı çıktı
		assertThat(s1).isEqualTo(s2);
		// Anahtarlar alfabetik gelsin
		assertThat(s1).startsWith("a=1&").contains("m=two").endsWith("z=3");
	}
	
	@Test
	@DisplayName("redeliveryCount: x-death header'ından sayıyı okur")
	void redeliveryCount_readsFromHeaders() {
		Map<String, Object> death = new HashMap<>();
		death.put("count", 4);
		List<Map<String, Object>> xDeath = List.of(death);
		Map<String, Object> headers = new HashMap<>();
		headers.put("x-death", xDeath);
		
		assertThat(helper.redeliveryCount(headers)).isEqualTo(4);
		assertThat(helper.redeliveryCount(Collections.emptyMap())).isZero();
	}
	
	@Test
	@DisplayName("isTransient: ResourceAccessException → geçici")
	void isTransient_resourceAccess() {
		assertThat(helper.isTransient(new ResourceAccessException("timeout")))
				.isTrue();
	}
	
	@Test
	@DisplayName("isTransient: 5xx → geçici, 4xx → kalıcı")
	void isTransient_httpCodes() {
		var h = new HttpHeaders();
		var ex5xx = HttpServerErrorException.create(
				HttpStatus.BAD_GATEWAY, "bad gw", h, null, null);
		var ex4xx = HttpClientErrorException.create(
				HttpStatus.BAD_REQUEST, "bad req", h, null, StandardCharsets.UTF_8);
		
		assertThat(helper.isTransient(ex5xx)).isTrue();
		assertThat(helper.isTransient(ex4xx)).isFalse();
	}
	
	@Test
	@DisplayName("retryAfterSeconds: sayısal saniye ve RFC1123 tarihini parse eder")
	void retryAfterSeconds_parsing() {
		HttpHeaders numeric = new HttpHeaders();
		numeric.add(HttpHeaders.RETRY_AFTER, "12");
		var exNumeric = HttpClientErrorException.create(
				HttpStatus.TOO_MANY_REQUESTS, "rate", numeric, null, null);
		
		assertThat(helper.retryAfterSeconds(exNumeric)).contains(12L);
		
		String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
				ZonedDateTime.now().plusSeconds(30));
		HttpHeaders dateHdr = new HttpHeaders();
		dateHdr.add(HttpHeaders.RETRY_AFTER, httpDate);
		var exDate = HttpClientErrorException.create(
				HttpStatus.TOO_MANY_REQUESTS, "rate", dateHdr, null, null);
		
		assertThat(helper.retryAfterSeconds(exDate)).isPresent();
		assertThat(helper.retryAfterSeconds(exDate).get()).isBetween(0L, 60L);
	}
	
	@Test
	@DisplayName("chooseDelayMs: deaths indeksine göre gecikme, 429 varsa Retry-After'ı dikkate alır")
	void chooseDelayMs_behavior() {
		List<Long> defaults = List.of(3000L, 10000L, 30000L);
		
		// deaths=0 → 1. deneme → 3000
		long d0 = helper.chooseDelayMs(new RuntimeException("x"), 0, defaults, true);
		assertThat(d0).isEqualTo(3000L);
		
		// deaths=5 (upper bound) → son eleman 30000
		long d5 = helper.chooseDelayMs(new RuntimeException("x"), 5, defaults, true);
		assertThat(d5).isEqualTo(30000L);
		
		// 429 + Retry-After: 5s → 5000ms, base=3000 → max(base, 5000)=5000
		HttpHeaders hdr = new HttpHeaders();
		hdr.add(HttpHeaders.RETRY_AFTER, "5");
		var ex429 = HttpClientErrorException.create(
				HttpStatus.TOO_MANY_REQUESTS, "rate", hdr, null, null);
		
		long d429 = helper.chooseDelayMs(ex429, 0, defaults, true);
		assertThat(d429).isEqualTo(5000L);
	}
	
	@Test
	@DisplayName("buildIdemKey: parametre sırası değişse de aynı hash")
	void buildIdemKey_consistency() {
		Map<String, Object> p1 = new HashMap<>();
		p1.put("code", "123456");
		p1.put("user", "alice");
		
		Map<String, Object> p2 = new LinkedHashMap<>();
		p2.put("user", "alice");
		p2.put("code", "123456");
		
		MailSendRequest r1 = new MailSendRequest(
				"alice@example.com", "Verify", "<b>hi</b>", "hi", MailKind.OTP, p1);
		MailSendRequest r2 = new MailSendRequest(
				"alice@example.com", "Verify", "<b>hi</b>", "hi", MailKind.OTP, p2);
		
		String k1 = helper.buildIdemKey(r1);
		String k2 = helper.buildIdemKey(r2);
		
		assertThat(k1).isEqualTo(k2);
		assertThat(k1).startsWith("mail:id:");
		assertThat(k1.length()).isGreaterThan(20);
	}
}