package com.berkayb.soundconnect.shared.mail.helper;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpHeaders;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class MailJobHelper {
	private final StringRedisTemplate redis;
	
	// ----------------- Retry Siniflandirmasi -----------------
	
	/**
	 * Hatanin gecici mi kalici mi oldugunu belirler.
	 * - Gecici: network hatalari, timeout'lar, 5xx HTTP hatalari -> reque edilebilir.
	 * - Kalici: 4xx HTTP hatalari -> DLQ'ya gondericez */
	public boolean isTransient(Exception e) {
		// Network hatalari (timeout, baglanti kesilmesi vs) genelde gecicidir
		if (e instanceof ResourceAccessException) {
			return true; // genelde SockecTimeout/ConnectTimeout icerir
		}
		// HTTP durum koduna gore hata turunu ayikla
		if (e instanceof HttpStatusCodeException httpEx) {
			int status = httpEx.getStatusCode().value();
			if (status >= 500) return true; // 5xx -> sunucu hatasi -> gecici
			if (status >= 400) return false; // 4xx -> istemci hatasi -> kalici (DLQ)
		}
		// tanimlayamadigimiz hatalari varsayilan olarak gecici sayiyoruz :D
		return true;
	}
	
	// ----------------- x-death / Redelivery Count -----------------
	/**
	 * RabbitMQ header'larindan x-death sayisini dondurur.
	 * Poison message'lari sosnuza kadar requeue etmemek icin kullanilir.
	 */
	@SuppressWarnings("unchecked")
	public int redeliveryCount(Map<String, Object> headers) {
		try {
			Object xDeath = headers.get("x-death");
			if (xDeath instanceof List<?> list && !list.isEmpty()) {
				Object last = list.get(0);
				if (last instanceof Map<?, ?> m) {
					Object c = m.get("count");
					if (c instanceof Number n) {
						return n.intValue();
					}
				}
			}
		} catch (Exception ignore) {}
		return 0;
	}
	
	// ----------------- Idempotency & Lock -----------------
	
	/**
	 * Redis SETNX + TTL kullanarak idempotency kontrolu yapar
	 * Amac: ayni mesajin birdne fazla kes islenmesini engellemek.
	 * SETNX: eger key yoksa yaz, varsa dokunma
	 * TTL: belirli bir sure sonra redisten silinsin
	 * Fail-safe: redis'e erisilemezse islem yine de devam eder. */
	public boolean setIfAbsent(String key, String val, Duration ttl) {
		try {
			Boolean ok = redis.opsForValue().setIfAbsent(key, val, ttl);
			return Boolean.TRUE.equals(ok);
		} catch (DataAccessException ex) {
			// redis yoksa idempotency devre disi kalsin
			log.warn("Idempotency check FAILED (redis). key={}, err={}", key, ex.toString());
			return true;
		}
	}
	
	/**
	 * daha once bu is basariyla gonderilmis mi?
	 * Redisde sentKey varsa tekrar islemeye gerek yok
	 * Fail-closed: redise eriselemezse false doner -> tekrar denemeye izin verir. */
	public boolean isAlreadySent(String sentKey) {
		try {
			String v = redis.opsForValue().get(sentKey);
			return v != null;
		} catch (DataAccessException ex) {
			log.warn("Sent-check Failed (redis). key={}, err={}", sentKey, ex.toString());
			return false; // fail-closed: tekrar dene
		}
	}
	
	// Ayni payload'i ayni anda ikinci worker islemeye calismasin diye kisa sireli lock
	public boolean acquireLock (String lockKey, Duration ttl) {
		return setIfAbsent(lockKey, "1", ttl);
	}
	
	// basarili gonderimden sonra "sent" isareti
	public void markSent(String sentKey, Duration ttl) {
	try {
		// Debug kolayligi icin timestamp yazalim.
		String val = "1@" + Instant.now();
		redis.opsForValue().setIfAbsent(sentKey, val, ttl);
		} catch (DataAccessException ex) {
		log.warn("Mark-sent FAILED (redis). key={}, err={}", sentKey, ex.toString());
		}
	}
	
	// Lock'u birak
	public void releaseLock(String lockKey) {
		try {
			redis.delete(lockKey);
		} catch (DataAccessException ex) {
			log.warn("Release-lock FAILED (redis). key={}, err={}", lockKey, ex.toString());
		}
	}
	
	// ----------------- Id Hash (prefixsiz) -----------------
	
	/**
	 * Mail gonderim istegi icin benzersiz bir idempotency anahtari uretir
	 * icerik: to | subject | kind | params -> SHA256 hash
	 * Amac: ayni icerikli e-postalarin etekrar gonderilmesini engellemek
	 * Format: mail:id:{sha256} */
	public String buildIdemKey(MailSendRequest req) {
		String raw = String.join("|",
		                         nullSafe(req.to()),
		                         nullSafe(req.subject()),
		                         req.kind() == null ? "null" : req.kind().name(),
		                         stableParamsString(req.params())
		);
		return "mail:id:" + sha256(raw);
	}
	
	/**
	 * parametreleri deterministik sekilde string'e cevirir.
	 * amac: ayni parametre seti her zaman ayni hash'i uretsin
	 */
	public String stableParamsString(Map<String, Object> params) {
		if (params == null || params.isEmpty()) return "";
		return params.entrySet().stream()
		             .sorted(Map.Entry.comparingByKey()) // sıralama → deterministik hash
		             .map(e -> e.getKey() + "=" + Objects.toString(e.getValue()))
		             .reduce((a, b) -> a + "&" + b)
		             .orElse("");
	}
	
	/**
	 * SHA256 hash uretir/.
	 * - Amac: idempotency key icin sabit guvenli bir temsil olusturmak
	 * - Fallback: SHA256 basarisiz olursa basit hashCode kullanilir.
	 */
	public String sha256(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(dig);
		} catch (Exception ex) {
			return Integer.toHexString(input.hashCode()); // fallback
		}
	}
	
	public String nullSafe(String s) {
		return s == null ? "" : s;
	}
	
	// ----------------- PII Masking & Hata Log -----------------
	
	/**
	 * e-posta adresini maskeleyerek log'larda gizlilik saglar
	 * format:  a***@g***.com
	 * PII (kisisel veri) loglara dusmesin.*/
	public String maskEmail(String email) {
		if (email == null) return "null";
		int at = email.indexOf('@');
		if (at <= 1) return "***";
		String local = email.substring(0, at);
		String domain = email.substring(at + 1);
		String maskedLocal = local.charAt(0) + "***";
		int dot = domain.indexOf('.');
		String maskedDomain = (dot > 1)
				? domain.charAt(0) + "***" + domain.substring(dot)
				: domain.charAt(0) + "***";
		return maskedLocal + "@" + maskedDomain;
	}
	
	/**
	 * Mail gonderim hatasini log'lar
	 * - hata turune gore farkli log formati kullanilir.
	 * - e posta adresi maskelenir
	 * - requue bilgisi loga eklenir -> hata yonetimi izlenebilirligi icin
	 */
	public void logErrorForSend(MailSendRequest req, Exception e, boolean requeue) {
		// PII'yi maskele
		final String maskedTo = maskEmail(req.to());
		
		// HTTP bazlı hatalar (4xx/5xx + 429 özel durumu)
		if (e instanceof HttpStatusCodeException httpEx) {
			final int code = httpEx.getStatusCode().value();
			
			// 429: Rate Limit — Retry-After bilgisini (saniye) çekmeye çalış
			if (code == 429) {
				Optional<Long> ra = retryAfterSeconds(httpEx);
				log.error("Mail send FAILED (HTTP 429 RATE LIMIT) -> requeue={}, retryAfter={}s, kind={}, to={}, subject={}, body={}",
				          requeue,
				          ra.orElse(null),
				          req.kind(),
				          maskedTo,
				          req.subject(),
				          safeBody(httpEx));
				return;
			}
			
			// Diğer 4xx/5xx durumları
			log.error("Mail send FAILED (HTTP {}) -> requeue={}, kind={}, to={}, subject={}, body={}",
			          code,
			          requeue,
			          req.kind(),
			          maskedTo,
			          req.subject(),
			          safeBody(httpEx));
			return;
		}
		
		// Ağ/timeout hataları
		if (e instanceof ResourceAccessException) {
			log.error("Mail send FAILED (timeout/network) -> requeue={}, kind={}, to={}, subject={}, err={}",
			          requeue,
			          req.kind(),
			          maskedTo,
			          req.subject(),
			          e.toString());
			return;
		}
		
		// Bilinmeyen/kapsüllü başka hatalar
		log.error("Mail send FAILED (unknown) -> requeue={}, kind={}, to={}, subject={}, err={}",
		          requeue,
		          req.kind(),
		          maskedTo,
		          req.subject(),
		          e.toString());
	}
	
	/**
	 * HTTP hatasinda response body'sini guvenli sekilde alir.
	 * amac: loglara dusecek body icerigini kontrol etmek
	 * hata olursa "<no-body>" doner
	 */
	public String safeBody(HttpStatusCodeException ex) {
		try {
			return ex.getResponseBodyAsString();
		} catch (Exception ignored) {
			return "<no-body>";
		}
	}
	
	// 429 mu?
	public boolean isRateLimited(Exception e) {
		if (e instanceof HttpStatusCodeException httpEx) {
			return httpEx.getStatusCode().value() == 429;
		}
		return false;
	}
	
	/**
	 * Retry-After header'ını saniye cinsinden döndürür.
	 * - Numeric (saniye) veya HTTP-date olabilir.
	 * - Yoksa Optional.empty().
	 */
	public Optional<Long> retryAfterSeconds(HttpStatusCodeException httpEx) {
		try {
			String val = httpEx.getResponseHeaders() != null ? httpEx.getResponseHeaders()
			                                                         .getFirst(HttpHeaders.RETRY_AFTER) : null;
			if (val == null || val.isBlank()) return Optional.empty();
			
			// 1) Sayısal saniye ise
			try {
				long seconds = Long.parseLong(val.trim());
				if (seconds >= 0) return Optional.of(seconds);
			}
			catch (NumberFormatException ignore) { /* date olabilir */ }
			
			// 2) HTTP-date ise (örn: Sun, 06 Nov 1994 08:49:37 GMT)
			try {
				ZonedDateTime when = ZonedDateTime.parse(val, DateTimeFormatter.RFC_1123_DATE_TIME);
				long secs = ChronoUnit.SECONDS.between(ZonedDateTime.now(when.getZone()), when);
				if (secs < 0) secs = 0;
				return Optional.of(secs);
			}
			catch (Exception ignore) {
			}
			
			return Optional.empty();
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}
	
	public long chooseDelayMs(Exception e, int deaths, List<Long> defaultDelaysMs, boolean useRetryAfter) {
		// Attempt index: deaths 0->1.attempt, 1->2.attempt ...
		int idx = Math.min(deaths, Math.max(0, defaultDelaysMs.size() - 1));
		long base = defaultDelaysMs.isEmpty() ? 3000L : defaultDelaysMs.get(idx);
		
		if (useRetryAfter && isRateLimited(e) && e instanceof HttpStatusCodeException httpEx) {
			return retryAfterSeconds(httpEx).map(s -> Math.max(base, s * 1000L)).orElse(base);
		}
		return base;
	}
}