# Aşama 01 — gerçek kabul ve paket kaydı

8 Ekim 2026. **Çalışan kabul kaydı; aşama henüz kapanmadı.** Kullanıcının bu
oturuma özel kararıyla mobil kabul Android Studio emülatöründedir. Fiziksel
Vivo veya insan görsel onayı bu teknik sonuçlardan türetilmez.

## Kaynak ve paket

| Alan | Doğrulanan kimlik |
| --- | --- |
| Backend branch / başlangıç HEAD | `ready-for-prod-01` / `c537c27ce512d0c0c05063092a898c8bb5383da5` |
| Frontend branch / başlangıç HEAD | `ready-for-prod-01` / `f68140a843e631bbd69dfada9716b9c67f7ae935` |
| Çalışma ağacı | Bu HEAD'lerin üzerinde commit edilmemiş Aşama 01 değişiklikleri |
| Son JAR SHA-256 | `ed1ba65697aa9fceb1c23b0620d0087aff8cc20893ce7a4322ff8b80459b34a1` |
| Çalışan API image | `sha256:249a4ddc6d7734fa7b84318bc78aaa18ca77e73337e10a2ad73408a97ebcf7b7` |
| Son normal debug APK SHA-256 | `8122a81c10d8fc272a342faf1a61111eb806a9101dc8888abcbeef2bf0e67b34` |
| APK adı / boyut | `ready01-final03-debug.apk` / 225026836 bayt |
| Mobil ortam | `emulator-5554`, `tr.com.soundconnect.app`, push açık, diagnostics açık, ortam `local`, localhost API |

APK install-r ile verileri koruyarak yüklendi. Kurulu APK SHA-256 değeri yerel
final03 ile birebir eş. Backend container içindeki JAR da yukarıdaki paketle
eş. HEAD tek başına yeni kaynak kimliği değildir: üst çalışma alanındaki
`artifacts/ready-for-prod-01-20261008/runtime/source-manifest.json`, backend
2234 ve frontend1623 kaynak/test/ilgili yapılandırma girdisinin hashlerini tutar.

Bu belgedeki **W/** üst `SoundConnect/` çalışma alanını, **BE/** bu backend
reposunu belirtir. Büyük APK/JAR, ham günlük ve özel veri Git'e eklenmez.

## Gerçek HTTP, kalıcılık ve realtime

| Yol / beklenen sonuç | Gözlenen sonuç | Kanıt |
| --- | --- | --- |
| İki reset öncesi JWT, resetten sonra reddedilmeli; yeni giriş çalışmalı | Sahipli gerçek PG/Redis/Rabbit fixture'ında iki eski mutation401, yeni200; OTP tüketimi ve version/parola kalıcılığı doğrulandı | `BE/artifacts/ready-for-prod-01/backend/01-a/http-realtime-run/` |
| Reset öncesi açık STOMP bağlantıları artık ürün etkisi oluşturmamalı | Eski iki bağlantıya outbound ürün teslimi engellendi; yeni subscriber teslim aldı; inbound güvenliği hedefli testte ayrı | Aynı fixture ve [A notları](01-A-NOTLAR.md) |
| Redis limiter arızası bypass yerine503/Retry-After5 üretmeli; iyileşince giriş çalışmalı | Gerçek izole Redis pause sırasında login/forgot/reset503; recovery200. Ortak Redis durdurulmadı | `http-realtime-run/`, `http-monitor-run/` |
| Yerel son uygulama normal açılmalı ve veri korumalı | 39.37sn gerçek startup, readiness200/UP, Dockerhealthy. İki additive şema, mevcut36kullanıcı/540inbox/kampanya hashleri korundu | `W/artifacts/ready-for-prod-01-20261008/runtime/result.json` |
| Sağlık API'si yalnız yetkili admin tarafından okunmalı | Admin health/recent200; token yok401; mevcut normal müzisyen her iki endpoint403 | Aynı dizinde `api-acceptance.json` |
| Ölçümler bilinmeyeni sağlıklı göstermemeli | 25bileşenden24UP; API trafiksiz aralıkta UNKNOWN/NO_TRAFFIC. Storage ve worker pasif probeUP; admin okuması queue tüketmedi | Aynı dizinde `health-snapshot.json` |

Shared DB/Redis/Rabbit container kimliği ve başlama zamanı rollout sırasında
korundu. API ve medya worker yetkili dar rollout kapsamında yenilendi;
worker image değişmedi. İlk başarısız startup güvenli eski sürüme döndü;
ApplicationReadyEvent lifecycle düzeltmesi sonrası son JAR kabul edildi.
Önceki başarısız paket veya partial test yeni PASS sayılmaz.

## Son APK ile emülatör kabulü

1. Mevcut admin oturumu ile panel → Sistem sağlığı açıldı. Yetkili sağlık
   kartları ve önceki son hata kaydı görüldü. Oturum/uygulama verisi korunarak
   son APK'ya geçildi.
2. “Deneme raporu gönder” seçildi. Final03 raporu
   `35c3874e-4be3-4a9b-b593-18e0ef470a87`,
   `2026-10-08T06:55:56.710077Z` zamanında gerçek PostgreSQL'e kaydoldu;
   source `DIAGNOSTICS_CHECK`, errorType `DiagnosticAcceptanceCheck`, ortam
   `local`. Aynı zamanlı “Deneme raporu · Hata” satırı admin listesinde görüldü.
   Final02'nin önceki tek deneme kaydı korundu; yinelenen tekrar değildir.
3. Yalnız5554'ün `tcp:8080` ADB reverse bağlantısı80sn kaldırıldı. Sunucu ve
   ortak bağımlılıklar çalışmaya devam etti. Eski ölçüm tutuldu; üst özet
   “Ölçüm eski”, güncel bilgi alınamadı ve eski hata listesi uyarıları görüldü.
4. Reverse finally ile geri açıldı. Sonraki otomatik yenilemede güncel ölçüm
   geldi, eski ölçüm uyarısı kalktı. Aynı admin oturumu korundu. Bu kabul yeni
   e-posta üretmedi; sürekli monitor açık değildi.

Kanıtlar **W/artifacts/ready-for-prod-01-20261008/frontend/** altında:
`final03-report-accepted.png`, `final03-stale.png/xml`,
`final03-recovered.png/xml`, `network-acceptance.json/log`.
Bu teknik akışlar **PASS**. Rapor sentetik uygulama tanılama olayıdır; native
crash/ANR, login öncesi hata veya bütün cihazlardan teslim garantisi değildir.

**Şifre reset sonrası gerçek mobil eski oturum → yeniden giriş: PASS.**
Mevcut emülatörde boş uygulama verisine sahip geçici Android test kullanıcısı10
ve yalnız sahip olunan bir test hesabı kullanıldı. Eski parola ile normal APK
girişi → kendi OTP fixture'ıyla gerçek reset HTTP200 → eski bearer ve eski
parola401 → eski mobil oturumda Mesajlar açılınca otomatik giriş ekranı → yeni
parolayla normal giriş ve korumalı Mesajlar ekranı doğrulandı. Yeni API token200,
owned session_version1. Forgot e-postası gönderilmedi; OTP yalnız bu test hesabına
hazırlandı. Gerçek forgot/Rabbit confirm yolu ayrı izole HTTP kabulündedir.

Asıl Android kullanıcısı0'a dönüldü ve mevcut admin sağlık ekranı aynı oturumla
geri geldi. Geçici kullanıcı10 OS tarafından kaldırıldı; user0 uygulama verisi
clear/copy/export edilmedi. Başlangıç36kullanıcı,9rol,35rolbağı,16müzisyenprofili,
540inbox,105kampanya,99occurrence ve190recipient ID+hashleri aynıdır.
Veritabanında sahipli1testhesabı/profili/rolbağı kanıt için kaldı; toplam37hesap.
Kanıt **W/artifacts/ready-for-prod-01-20261008/mobile-auth/result.json**;
stabil görseller `new-login-username.png`, `new-session-messages-stable.png`,
`primary-user0-return.png`. İlk `revoked-session-result.png` geçiş animasyonudur,
nihai giriş görüntüsü sayılmaz. Gboard çubuğu Android test kullanıcısına aittir.

Mobil kabul sırasında sentetik düğmeden bağımsız bir `FRAME_TIMING` /
`FrameBudgetExceeded` olayı da gerçek DB ve admin listesine ulaştı:
`67bda7f6-69b4-4f34-9d0d-0f89d31ba567`, `07:14:25.558763Z`, ortamlocal.
Bu, sınırlı akıcılık gözleminin gerçek iletimidir; yoğun yerel emülatör koşulu
bir üretim performans kusuru veya SLA ölçümü olarak genellenmez.

## Observability v1 kapsamının sınırı

Kullanıcının8Ekim kapsam sorusu üzerine mevcut kaynakla tekrar doğrulandı:
API ölçümü son pencere için istek sayısı,5xx hata oranı ve **ortalama** yanıt
süresidir; yalnız bu API örneğini kapsar.25bileşen sağlık/birikme/başarısız iş,
worker ve salt okunur S3 erişimi görünürlüğü verir. Mobil tarafta güvenli hata
özeti ve sınırlı tanılama kaydı vardır. Tam observability ürünü iddia edilmez.

CPU/RAM/disk/JVM paneli, kalıcı zaman serileri ve geçmiş grafikler, p95/p99,
merkezi backend exception araması/dosya-satır inceleme ve dağıtık istek izleme
bu ilk sürümde uygulanmadı. S3 erişim probe'u upload/CDN uçtan uca kabulü
değildir. Sürekli dış izleme/bağımsız host ve üretim alarm işletimi henüz yok.
Bu sınırlar01'in tanımlı v1 kabulünü genişletmez; sonraki iş kendiliğinden açılmaz.

## Harici alarm teslimi

Kullanıcının seçtiği iki alıcıya yalnız izin verilen iki TEST mesajı gönderildi:
kesinti ve toparlanma. Gerçek izole HTTP connector kesintisinde üçüncü15sn
DOWN gözlemi FIRING, yeniden açılıştaki ikinciUP RECOVERED üretti. İlk normal
ölçüm, aynı arızanın state reload sonrası tekrarı ve recovery sonrası normal
ölçüm sessiz kaldı.

MailerSend tekil mesaj API'si iki mesajın toplam dört alıcı kopyasını
**delivered** olarak doğruladı. Bağlı Gmail hesabında iki INBOX mesajı ayrıca
doğrulandı. Kullanıcının okuduğu iddia edilmez. İlk403/1010 yanıtı provider
kabulü değildi; sonraki gerçek kabulden ayrı korunur. Bir daha test gönderilmedi.

Kanıt: **W/artifacts/ready-for-prod-01-20261008/runtime/** altında
`alarm-acceptance.jsonl`, `alarm-receipt-summary.json`,
`provider-delivery-check.json`. Alıcı/key/token/raw provider kayıtları özel,
Git dışı klasördedir. Hiçbir yeni hizmet/abonelik veya account ayarı oluşturulmadı.

Bu, aynı bilgisayardaki gerçek HTTP connector kesintisini kapsar. Tüm hostun,
elektriğin veya ağın kaybı ile bağımsız dış host/dead-man kabulü **YAPILMADI**;
kullanıcı yalnız yerel ortama sahiptir. Sürekli izleyici kurulmadı. Prod
barındırma ve işletim yapılandırması sonraki06/08 kapsamından tamamlanmış sayılmaz.

## Ayrı kabul katmanları ve açık kapılar

- Otomatik test/build: [bağımsız incelemedeki tablo](BAGIMSIZ_INCELEME.md).
  Son tam backend753suite/6056PASS/0hata;119hamXMLatlama,101parametresiz kayıt
  ile18parametreşablonundan oluşur. Bunlar mevcut opt-in/Disabled kapılarıdır;
  aşamayla ilgili56suite/454testte0skip vardır. Önceki ve örtüşen sayılar toplanmaz.
  Mevcut migration launcher regresyonu ayrıca **19 senaryo/307 kontrol PASS**:
  `W/artifacts/ready-for-prod-01-20261008/ci-migrations/verify-push-migrations.log`.
  Bu betik whitelist'teki15eskiSQL'yi sınar; yeni iki8EkimSQL'nin gerçek
  çalıştırılma kabulü yukarıdaki runtime/PG kayıtlarındadır. Fixture kendi
  portsuz/tmpfs PostgreSQL container'ıydı; shared servis kullanılmadı.
- Kaynak incelemesi: bulgular ve yazar/incelemeci sınırı aynı raporda.
- Gerçek API/DB/realtime ve son APK sağlık/rapor/kesinti-toparlanma/eski oturum:
  yukarıdaki dar kapsamda PASS.
- Fiziksel telefon: bu oturumun doğrudan kullanıcı kararıyla kullanılmadı.
- **İnsan görsel onayı: ALINDI — 8 Ekim 2026.** Kullanıcı, son APK ve gösterilen
  sağlık/reset-yeniden giriş ekran/akışları için “okey gerekli kontrolleri
  yaptıysan onaylıyorum o zaman” dedi. Öncesinde ilgili teknik kontroller
  tamamlanmıştı. Kabul final03APK ve gösterilen kapsam içindir; kullanıcının
  bilgisayarda bizzat yeniden test yaptığı veya fiziksel/prod kabulü değildir.
- Commit/push/PR/ana dal CI/merge: PLAN'daki mevcut yetkiyle KAPANIŞ SÜRÜYOR.
  Her adım gerçek sonucu ile çalışma kaydına yazılır; henüz CI/merge PASS yok.
  Backend workflow'una monitor Python test adımı eklendi; mevcut CI
  korumaları aynen durur. Workflow kaynağı incelemesi hostedCI sonucu değildir.
- Canlı dağıtım, yeni bildirim matrisi, Analytics ve02 ürün geliştirmesi yok.
