# 01-B — auth limiter kesintisi

8 Ekim 2026. Kapsam: mevcut `AuthRateLimiter`, HTTP IP filtresi ve hesap guard'ı.
Başlangıç backend HEAD `c537c27c`; branch `ready-for-prod-01`. Bu belge alt işin
kanıt kaydıdır; aşama kapanışı veya gerçek HTTP kabulü yerine geçmez.

## Kaynak bulgusu ve çözüm

- Önceki limiter Redis exception'ında ve `null` sayaçta izin veriyordu. Hesap/IP
  boyutları aynı limiter'ı kullandığı için iki koruma birlikte bypass olabiliyordu.
- Var olan fixed-window Redis altyapısı korundu. Tek atomik script sayaç ve kalan
  TTL'yi birlikte döndürür; ayrı TTL network çağrısı kaldırıldı. Süresiz eski
  sayaçta quota sıfırlanmadan mevcut policy süresi atanır.
- Exception, boş/yanlış boyutlu sonuç, geçersiz tip, sıfır/negatif sayaç veya
  negatif TTL: istek reddedilir. IP filtresi ve hesap guard'ı aynı **503 / 1115
  AUTH_RATE_LIMIT_UNAVAILABLE / Retry-After: 5** sözleşmesini verir. Sabit kısa
  bekleme altyapı/hesap ayrıntısı taşımaz; otomatik sınırsız retry eklenmedi.
- Gerçek kota aşımı **429 / 1104 AUTH_RATE_LIMITED** olarak korunur. Retry-After
  alt sınırı 1 saniye, üst sınırı ilgili policy penceresidir. Redis düzeldiğinde
  bir sonraki sağlıklı kontrol geçerli kotayla devam eder; yerel fail-open fallback yok.
- Mevcut `ServiceUnavailableRetryException` ve `GlobalExceptionHandler` kullanıldı.
  Yeni exception/ikinci limiter/proxy/RBAC katmanı eklenmedi.
- Redis anahtarlarında mevcut SHA-256 IP/hesap digest'leri ve endpoint boyutları
  korunur. Uyarı 60 saniyede en çok bir kez yalnız exception sınıfını yazar;
  ham kimlik, Redis adresi/parolası ve exception metni loglanmaz.

## Kullanım ve production wiring

`SecurityConfig` IP filtresini JWT filtresinden önce yerleştirir; servlet otomatik
kaydının kapalı oluşu çift quota tüketimini önler. Dokuz POST auth endpoint'i
korunur: login, Google, kayıt, OTP doğrulama/yeniden gönderim, username uygunluğu,
reset hesap bulma/istek/onay. Hesap guard'ı ayrıca hesap silme yeniden doğrulamasını
korur. Login guard'ı DB/parola kontrolünden önce çalışır; reset hesap anahtarı mevcut
username/e-posta eşlemesini ve hesap kimliği birleştirmesini korur.

`application-prod.yml` + `ProductionSafetyValidator` auth limiter'ın açık olmasını
ve readiness grubunda Redis'i zorlar. Bağlantı süresi en çok 2 saniye, komut süresi
en çok 3 saniye; mevcut varsayılanlar 1/2 saniyedir. Readiness yönlendirme gecikmesi
sırasında veya uygulamaya doğrudan erişimde bu request guard'ı ayrıca kapalı kalır.
Fleet/ingress gerçek ayarları bu alt işte doğrulanmış değildir. Redis bağlantıları
mevcut `StringRedisTemplate` yaşam döngüsünde kullanılır; elle bağlantı açılmaz.

## Otomatik doğrulama

Yeni/uyarlanan test kapsamı:

- unit: invalid result matrisi, outage/recovery, bounded TTL, tek round-trip,
  digest anahtarlar ve rate-limited mahrem log;
- guard: on hesap boyutunda 503; 429/normalization mevcut sözleşmesi;
- MockMvc: dokuz auth POST yolunda IP kesintisi; IP geçtikten sonra hesap guard
  kesintisi; aynı instance recovery; 429 ve 503 HTTP/header/body farkı;
- disposable Testcontainers Redis: çok-instance eşzamanlı quota, IP/hesap/endpoint
  ayrımı, doğal expiry, eski süresiz sayaç, bozuk sayaç, owned container pause ile
  gerçek command timeout ve aynı bağlantılarla toparlanma.

**Sonuç: 107 PASS, 0 FAIL, 0 ERROR, 0 SKIP.** Auth limiter/guard/filter/wiring/proxy
69 test (gerçek disposable Redis IT 4 dahil); production config ve ortak exception
regresyonları 38 test. `compileJava` ve `compileTestJava` tamamlandı. Koşu:

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 test `
  --tests 'com.berkayb.soundconnect.auth.ratelimit.*' `
  --tests 'com.berkayb.soundconnect.shared.config.ProductionSafetyValidatorTest' `
  --tests 'com.berkayb.soundconnect.shared.exception.ErrorTypeContractTest' `
  --tests 'com.berkayb.soundconnect.shared.exception.GlobalExceptionHandlerTest' `
  --console=plain
```

JDK 21.0.9; build süresi 1m02s. XML ve kaynak hashleri
`artifacts/ready-for-prod-01-20261008/01-b/` altında korundu. İlk komut sandbox
Gradle cache lock erişiminde durdu; aynı dar test komutuna yükseltilmiş erişim
onaylandı. İlk derleme eşzamanlı yazılan sağlık servisinin henüz bulunmaması
yüzünden durdu; o kaynak tamamlanınca yukarıdaki koşu başarılı oldu. Bunlar test
atlama veya kalite koruması gevşetme ile çözülmedi.

## Manuel kabul

Gerçek çalışan HTTP + izole Redis kesinti/iyileşme kabulü ana ajanla yürütülecek.
MockMvc HTTP sunucusu değildir; Testcontainers Redis otomatik testi kullanıcı
API kabulü sayılmaz. Ortak Redis/Rabbit/DB'ye kesinti uygulanmadı. Bu backend
guard kontrolü için telefon UYGULANAMAZ; mobil hata sunumu/emülatör ve insan
görsel kabulü Aşama 01'in ayrı kabul katmanlarıdır.
