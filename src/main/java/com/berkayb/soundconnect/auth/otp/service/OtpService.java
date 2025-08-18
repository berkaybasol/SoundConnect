package com.berkayb.soundconnect.auth.otp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 6 haneli otp kodu uretir ve Redis'te saklar. kodun dogrulugunu ve suresini kontrol eder.
 * brute-force ve rate-limit korumasi saglar.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {
	
	// asagida yazdigimiz butun OTP kodlarini burada redis template uzerinden String tipinde sakliyoruz.
	private final RedisTemplate<String, String> redisTemplate;
	
	// OTP'nin gecerli olacagi sure(3dk)
	private static final long OTP_EXPIRE_MINUTES = 3;
	
	// OTP kodunun uzunlugu (000000-999999 arası)
	private static final int OTP_LENGTH = 6;
	
	// kullanici kac kez girerse bloklansin?
	private static final int MAX_ATTEMPT = 5;
	
	// otp uretimi
	public String generateAndCacheOtp(String email) {
		// random OTP code uretiyoruz
		String otpCode = generateRandomOtpCode();
		
		// redis key-value seklinde calisir. burada emaili key olarak veriyoruz. bu key rediste o kullaniciya ait OTP
		// kodunu saklamak icin kullanilir
		String otpKey = buildOtpKey(email);
		
		// bahsettigimiz key-value'nin value kismi. yukarda random key uretmistik.
		// rediste ":0" retryCount'u ifade eder. 0 diyerek aslinda sunu yapmis oluyoruz: retryCount = 0
		// retryCount = kac kez denendi?
		String value = otpCode + ":0";
		
		// olusturdugumuz key-value'yu redise yazdiriyoruz.
		// opsForValue() ile String veri tipi kullaniyoruz. TTL suresiyle birlikte kaydediyoruz.
		// TTL = Time To Live -- yasam suresi bitince silinir. yukarida 3dk olarak tanimladik.
		redisTemplate.opsForValue().set(otpKey, value, Duration.ofMinutes(OTP_EXPIRE_MINUTES));
		
		log.info("OTP generated for email={} otp={} (expire {}m)", email, otpCode, OTP_EXPIRE_MINUTES);
		return otpCode;
	}
	
	// otp dogrulama ve retry handling
	public boolean verifyOtp(String email, String code) {
		
		String otpKey = buildOtpKey(email);
		ValueOperations<String, String> ops = redisTemplate.opsForValue();
		String value = ops.get(otpKey);
		
		if (value == null) {
			// kod suresi dolmus veya hic kod uretilmemis
			log.warn("OTP verify failed: no active code for {}", email);
			return false;
		}
		
		// "123456:2" -> kod:retryCount
		String[] parts = value.split(":");
		String cachedCode = parts[0];
		int retryCount = Integer.parseInt(parts[1]);
		
		if (retryCount >= MAX_ATTEMPT) {
			// cok fazla yanlis giris
			redisTemplate.delete(otpKey);
			log.warn("OTP blocked for {} after {} wrong attempts", email, retryCount);
			return false;
		}
		
		if (!cachedCode.equals(code)) {
			// yanlis kod girilirse retry arttir
			retryCount++;
			ops.set(otpKey, cachedCode + ":" + retryCount, redisTemplate.getExpire(otpKey), TimeUnit.SECONDS);
			log.warn("Wrong OTP for {} (attempt {}/{})", email, retryCount, MAX_ATTEMPT);
			return false;
		}
		
		// kod dogru. dogrulandiktan sonra silinir.
		redisTemplate.delete(otpKey);
		log.info("OTP verified successfully for {}", email);
		return true;
	}
	
	// retry kontrol
	public int getOtpRetryCount(String email) {
		String otpKey = buildOtpKey(email);
		String value = redisTemplate.opsForValue().get(otpKey);
		if (value == null) return 0;
		String[] parts = value.split(":");
		return Integer.parseInt(parts[1]);
	}
	
	// kod suresi kaldi mi?
	public long getOtpTimeLeftSeconds(String email) {
		String otpKey = buildOtpKey(email);
		Long expire = redisTemplate.getExpire(otpKey, TimeUnit.SECONDS);
		return expire == null ? 0 : expire;
	}
	
	// helper metodlar:
	
	// OTP kodu ureten metod:
	private String generateRandomOtpCode() {
		int number = new Random().nextInt((int) Math.pow(10, OTP_LENGTH));
		return String.format("%0" + OTP_LENGTH + "d", number);
	}
	
	// redis keyi email bazli uret. tekil ve cakismaz olsun
	private String buildOtpKey(String email) {
		return "OTP_EMAIL_" + email.toLowerCase();
	}
}