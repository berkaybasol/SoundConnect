# Marketplace medya işlemleri — PostgreSQL doğrulaması

20 Eylül 2026 tarihli son bağımsız medya turu: **17 test, 0 hata, 0 atlama**.
PostgreSQL 16.4 Testcontainers kullanıldı. Testler uygulama konfigürasyonunu veya
yerel bağlantı bilgilerini yüklemez; kendi geçici veritabanında çalışır.

| Suite | Çalışan test | Kapsam |
| --- | ---: | --- |
| `MarketplaceMediaTransactionPostgresTest` | 10 | 7 işlem/rollback/referans senaryosu ve 3 saat dilimi |
| `ProtectedImageLifecyclePostgresTest` | 7 | Thumbnail upload, gerçek CAS/commit, recovery, yarış ve silme |

Ortak `MediaJpaPostgresFixture`, gerçek `MediaAsset` ORM mapping'ini, production
UTC audit provider'ını, Spring transaction proxy'lerini ve **aynı
`JpaTransactionManager` üzerinden JDBC ile JPA'yı** kullanır. Marketplace
migration'ları uygulanır. İlgili marketplace referans sorgusu gerçek tablolarda
production SQL'iyle çalışır; ilgisiz modül referansları, profil/konum çözümleme ve
dış servisler bu dar fixture'ın dışındadır. Thumbnail testlerinde nesne deposu
bellekte, native dönüştürücü kontrollü fake'tir; S3/codec performansı ölçülmez.

## Kanıtlanan ve düzeltilen zaman sorunu

UTC `LocalDateTime` audit değeri, `hibernate.jdbc.time_zone=UTC` ile UTC olmayan
JVM'de JDBC'ye bağlandığında raw `timestamp` sütunu ile ORM'nin geri okuduğu saat
farklı olabilir. İstanbul senaryosu raw sütunda **-3 saat**, ORM round-trip'te
değişmeyen audit değerini doğruladı. Önceki scheduler cutoff'u raw JDBC
`Timestamp` ile bağladığı için iki karşılaştırma aynı zaman ekseninde değildi.
New York testinde **24 saat +30 saniyelik** sahipsiz fotoğraf `READY` kalıyordu.

`MediaAssetRepository.findMarketplaceOrphansBefore` cutoff'u audit alanıyla aynı
Hibernate `LocalDateTime` bağlama yolundan geçirir. Scheduler `PageRequest(0,100)`
ile sınırlı aday alır. İlan ve rapor referansları sorguda dışlanır; asıl silme
işlemi ilan ve asset kilitlerini aldıktan sonra yaş, durum, sahiplik ve
referansları yeniden kontrol eder. Aday okuması silme yetkisi sayılmaz.

UTC, İstanbul ve New York testleri aday listesini ve son durumu birlikte
doğrular: 24 saat -30 saniyelik görsel korunur, +30 saniyelik sahipsiz görsel
silme kuyruğuna girer; daha eski bağlı veya rapor kanıtı görselleri korunur.
Bu dar değişiklik mevcut timestamp verisini yeniden yazmaz ve genel ORM saat
mapping'ini değiştirmez.

## İşlem ve yarış güvenceleri

- Fotoğraf sırası, ilan sürümü ve JPA silme niyeti aynı commit'te değişir.
  PostgreSQL'in deferred FK ile **commit anında** hata vermesi hepsini geri
  alır; silme olayı ve upload rezervasyonu bırakma callback'i commit olmadan
  çalışmaz. Taslak silme ve hesap marketplace temizliği de sınanır.
- Update commit olup istemci yanıtı kaybolduğunda yeni bağlı görselin sonradan
  cleanup ile silinmesi referans kontrolünde reddedilir. Değiştirilen eski
  fotoğraf rapor kanıtıysa hesap temizliğinden sonra da korunur.
- Thumbnail upload'dan sonra DB commit başarısızsa dosya körlemesine silinmez;
  kalıcı eksik-varyant durumu sonraki backfill ile iyileşir. Commit başarıyla
  tamamlanıp yanıt kaybolması da kazanan türevi silmez.
- Eşzamanlı iki üretici aynı deterministik türevi güvenle bağlar. Silme veya
  kaynak değişimiyle yarışan eski üreticinin CAS'i reddedilir. Producer grace,
  son prefix temizliği, geçici storage hatası sonrası dayanıklı retry ve
  fiziksel silmeden hemen önce rapor referansı kontrolü doğrulanır.

## Tekrar çalıştırma ve sınırlar

Çalışan geliştirme backend'inin class dizinini paylaşmayan CI/izole checkout'ta:

```powershell
.\gradlew.bat test `
  --tests 'com.berkayb.soundconnect.modules.marketplace.media.MarketplaceMediaTransactionPostgresTest' `
  --tests 'com.berkayb.soundconnect.modules.media.image.ProtectedImageLifecyclePostgresTest'
```

Çalışan DevTools backend'iyle aynı checkout'ta yalnız `buildDirectory` ayırmak
yeterli değildir: Gradle proje execution history'si de ayrı tutulmalı;
JavaCompile, sourceSet, resources ve rapor çıktıları açıkça izole köke
bağlanmalı ve task graph başlamadan doğrulanmalıdır. Yerel tur bu guard,
ayrı `--project-cache-dir` ve `--no-build-cache` ile çalıştırıldı. Root'un
kendi entrypoint touch işlemi dışında normal class dosyaları değişmedi.

Yerel kanıtlar: `tmp/marketplace-quality-media-before-fix.xml`,
`tmp/marketplace-quality-media-tests-final.log` ve
`tmp/marketplace-quality-media-build/test-results/test/`.
Bu suite yük kapasitesi veya canlı CDN/gecikme ölçümü değildir. HTTP/JWT,
domain yarışları ve cihaz kabul testlerinin sonuçları ayrı doğrulama akışına
aittir; bu belge yalnız yukarıdaki 17 medya testini sayar.
