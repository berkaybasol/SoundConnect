package com.berkayb.soundconnect.auth.otp.service;

import com.berkayb.soundconnect.shared.util.EmailUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * 6 haneli otp kodu uretir ve Redis'te saklar. kodun dogrulugunu ve suresini kontrol eder.
 * brute-force ve rate-limit korumasi saglar.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Getter
public class OtpService {
	private final EmailUtils emailUtils;
	
	// asagida yazdigimiz butun OTP kodlarini burada redis template uzerinden String tipinde sakliyoruz.
	private final RedisTemplate<String, String> redisTemplate;
	
	
	
	// OTP'nin gecerli olacagi sure(3dk) from yml
	@Value("${otp.ttl.minutes:${mailersend.otp-validity-minutes:3}}")
	private long otpExpiredMinutes;
	
	// OTP kodunun uzunlugu (000000-999999 arası) from yml
	@Value("${otp.length:6}")
	private int otpLength;
	
	// kullanici kac kez girerse bloklansin? from yml
	@Value("${otp.max-attempt:5}")
	private int maxAttempt;
	
	// tekrar gonderim icin bekleme suresi from yml
	@Value("${otp.resend.cooldown.seconds:30}")
	private long resendCoolDownSeconds;
	
	private final SecureRandom secureRandom = new SecureRandom();
	
	
	// otp uretimi
	public String generateAndCacheOtp(String email) {
		// random OTP code uretiyoruz
		String otpCode = generateRandomOtpCode();
		
		// redis key-value seklinde calisir. burada emaili key olarak veriyoruz. bu key rediste o kullaniciya ait OTP
		// kodunu saklamak icin kullanilir
		String otpKey = buildOtpKey(emailUtils.normalize(email));
		
		// bahsettigimiz key-value'nin value kismi. yukarda random key uretmistik.
		// rediste ":0" retryCount'u ifade eder. 0 diyerek aslinda sunu yapmis oluyoruz: retryCount = 0
		// retryCount = kac kez denendi?
		String value = otpCode + ":0";
		
		// olusturdugumuz key-value'yu redise yazdiriyoruz.
		// opsForValue() ile String veri tipi kullaniyoruz. TTL suresiyle birlikte kaydediyoruz.
		// TTL = Time To Live -- yasam suresi bitince silinir. yml'de 3dk olarak tanimlayip yukarida @value olarak
		// verdik.
		redisTemplate.opsForValue().set(otpKey, value, Duration.ofMinutes(otpExpiredMinutes));
		
		log.info("OTP generated for email={} otp={} (expire {}m)", email, otpCode, otpExpiredMinutes);
		return otpCode;
	}
	
	// otp dogrulama ve retry handling
	public boolean verifyOtp(String email, String code) {
		
		String otpKey = buildOtpKey(emailUtils.normalize(email));
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
		
		if (retryCount >= maxAttempt) {
			// cok fazla yanlis giris
			redisTemplate.delete(otpKey);
			log.warn("OTP blocked for {} after {} wrong attempts", email, retryCount);
			return false;
		}
		
		if (!cachedCode.equals(code)) {
			// yanlis kod girilirse retry arttir
			retryCount++;
			ops.set(otpKey, cachedCode + ":" + retryCount, redisTemplate.getExpire(otpKey, TimeUnit.SECONDS),
			        TimeUnit.SECONDS); // (TTL saniye parametresi)
			log.warn("Wrong OTP for {} (attempt {}/{})", email, retryCount, maxAttempt);
			return false;
		}
		
		// kod dogru. dogrulandiktan sonra silinir.
		redisTemplate.delete(otpKey);
		log.info("OTP verified successfully for {}", email);
		return true;
	}
	
	// retry kontrol
	public int getOtpRetryCount(String email) {
		String otpKey = buildOtpKey(emailUtils.normalize(email));
		String value = redisTemplate.opsForValue().get(otpKey);
		if (value == null) return 0;
		String[] parts = value.split(":");
		return Integer.parseInt(parts[1]);
	}
	
	// kod suresi kaldi mi?
	public long getOtpTimeLeftSeconds(String email) {
		String otpKey = buildOtpKey(emailUtils.normalize(email));
		Long expire = redisTemplate.getExpire(otpKey, TimeUnit.SECONDS);
		return expire == null ? 0 : expire;
	}
	
	// helper metodlar:
	
	// OTP kodu ureten metod:
	private String generateRandomOtpCode() {
		int bound = (int) Math.pow(10, otpLength);
		int number = secureRandom.nextInt(bound);
		return String.format("%0" + otpLength + "d", number);
	}
	
	// redis keyi email bazli uret. tekil ve cakismaz olsun
	private String buildOtpKey(String email) {
		return "OTP_EMAIL_" + email.toLowerCase();
	}
	
	
	// resend cooldown key
	private String buildResendGuardKey(String email) {
		return "OTP_RESEND_GUARD" + email;
	}
	
	// resendden kalan cooldawn suresini getir
	public long getResendCooldownLeftSeconds(String email) {
		String key = buildResendGuardKey(emailUtils.normalize(email));
		Long expire = redisTemplate.getExpire(key, TimeUnit.SECONDS);
		return expire == null ? 0 : Math.max(expire, 0);
	}
	
	// resend'i baslatinca cooldown'i set et
	public void startResendCooldown(String email) {
		String key = buildResendGuardKey(emailUtils.normalize(email));
		redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(resendCoolDownSeconds));
	}
}