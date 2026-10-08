# 01-C izleyici ve bağımsız inceleme — 8 Ekim 2026

## Kapsam ve sonuç

Health/collector yazarı dışındaki ajan; `SystemHealthService`, `SystemHealthSources`,
`ApiHealthObservation`, `RabbitQueueHealthSources`, `StorageHealthObservation`,
health özellikleri/controller, mobile request/controller/rate guard/store/admin
controller, ek SQL ve ilgili unit/PostgreSQL testlerini salt okunur inceledi.
İlk bağımsız taramada yeni doğrulanmış P0/P1/P2 bulgu çıkmadı. Ana ajanın sonraki
prod profil kontrolü bir P2 yapılandırma açığı buldu: collector ortamı varsayılan
local kaldığı için production APK raporu400 ile reddedilecekti. Dar düzeltme
uygulandı: `application-prod.yml` ortamı `${SOUNDCONNECT_ENVIRONMENT:production}`,
base profil `${SOUNDCONNECT_ENVIRONMENT:local}`; açık staging/test ortamı aynı
değişkenle seçilebilir. Gerçek YAML/Binder ile base/prod/override testi
`MobileDiagnosticsConfigurationTest` eklendi; Gradle slotu sahibi C ajanının
bootJar öncesi koşusunda bu sınıf3 ve `ProductionConfigurationTest`2 olmak üzere
**5/5 PASS, 0 skip** sonucu alındı. C ajanın yakaladığı worker marker env bağlama
belirsizliği için base YAML'a `app.system-health.media-worker-health-file`
explicit `${APP_SYSTEM_HEALTH_MEDIA_WORKER_HEALTH_FILE:}` placeholder eklendi;
aynı test sınıfında gerçek `SystemEnvironmentPropertySource` ile compose env
adı da doğrulanır. Bu sonuç kaynak incelemesidir; çalışan API,
emülatör veya alıcı posta kutusu kabulünün yerine geçmez.

Kontrol edilen sınırlar: dört worker ve sınırlı kuyruk; timeout sonrası geç
ölçümün atılması, takılan probe'un çoğaltılmaması ve eskime; contributor verisinin
sabit kod/numerik ölçümlere indirgenmesi; yalnız 12KB JSON, sabit enum ve kaynak
konumu çerçeveleri; sessionVersion/FOR SHARE ile commit sınırı; ortam ayrımı,
event UUID/fingerprint tekrar engeli, satır/retention sınırı; admin yetkisi ve
son olay özetinde stack/hesap verisinin bulunmaması. HeadBucket ölçümü yükleme,
CDN veya nesne okuma kabulü değildir. Mobil metrik sağlıklı görünse de bu yalnız
collector DB ölçümüdür; native fatal yakalama veya bütün cihazlardan teslimi
kanıtlamaz.

## İzleyicide bağlı düzeltmeler

`scripts/monitoring/health_watch.py` için ana ajan yetkisiyle:

- Önceki gevşek state okuması şema2, sabit alanlar, tip/sınır, hex fingerprint,
  finite timestamp ve tekrar eden JSON alanı doğrulamasıyla değiştirildi.
- Dedup durumu, hedef URL/gönderici/alıcı kümesi/test modunun SHA-256 bağına
  bağlandı. Token yenilemesi dedup'u korur. Eski şema, bozuk dosya veya bağ
  uyuşmazlığı yeni hedefi sessizce susturmak yerine CONFIGURATION_REQUIRED döner.
  Credential ve açık hedef/alıcı değerleri kalıcı state'e yazılmaz.
- UP sonucu ölçüm zaman damgası, tam saniye yaşı ve HEALTHY nedeni tutarlı
  olmadıkça sağlıklı sayılmaz; eksik/tutarsız metadata UNKNOWN üretir.
  Başarılı BaseResponse zarfı da zorunludur.
- Uzun yeniden başlama aralığı veya geriye saat sıçraması ardışık gözlem sayısını
  yeniden başlatır; daha önce bildirilmiş olayın dedup kaydı korunur.
- MailerSend 202 yanıtındaki güvenli, en fazla256 karakter `x-message-id`
  yalnız `send_mail` çağıranına döner. Ana izleyici bunu loglamaz veya state'e
  yazmaz. Eksik/bozuk kimlik kabul edilen isteği yeniden göndertmez.
- GET sağlık isteği ve mail POST isteği dürüst
  `User-Agent: SoundConnect-Health-Monitor/1.0` gönderir. Ana ajanın mesajsız
  alıcısız sağlayıcı kontrolünde varsayılan urllib UA403/1010, ürün UA422
  required-fields dönmüştü; bu bilinen sağlayıcı sınırı için eklenmiştir.
  Tarayıcı taklidi yok; unit testleri iki gerçek Request header'ını doğrular.
- Son ürün incelemesinde normal boş API trafiğinin UNKNOWN/NO_TRAFFIC üzerinden
  yanlış FIRING/RECOVERED döngüsü oluşturabildiği RED testle doğrulandı. Yalnız
  `api/UNKNOWN/NO_TRAFFIC`, tam taze ve tutarlı timestamp/age, sıfır istek ve
  pozitif/sonlu/eskime sınırı içindeki pencere metrikleriyle nonactionable sayılır.
  Java/JAR/UI UNKNOWN ölçümü korunur; başka bileşenin unknown/stale/down/degraded
  alarmı ve bozuk/eksik metadata davranışı değişmez. Alarm UP yalnız işlem
  gerektiren sinyal bulunmadığıdır; boş trafikte latency/error iyileşmesi iddiası
  değildir. README bu ayrımı ve mevcut alarm toparlanması anlamını açıklar.

MailerSend'in [resmî sözleşmesi](https://developers.mailersend.com/api/v1/email)
202'yi asenkron sağlayıcı kabulü olarak tanımlar. Makbuz kimliği gerçek posta
kutusu teslimi veya kullanıcı okuması değildir.

## Otomatik doğrulama ve manuel kabul

`python -m unittest discover -s scripts/monitoring -p 'test_*.py' -v`:
İlk hedefte **12 PASS**, son boş trafik düzeltmesiyle **15 PASS**; gerçek
ağ/e-posta gönderimi yok. Baseline sessizliği,
restart/dedup/toparlanma, başarısız gönderim retry, stale/unknown, credential
reddi, payload mahremiyeti, hedef bağı, bozuk state, timestamp tutarlılığı,
mock sağlayıcı makbuzu ve saat/gözlem aralığı test edildi. Son üç test; normal
idle→traffic→idle akışının12 örnekte sessizliğini, başka bileşen alarmının üçüncü
örnekte FIRING üretmesini ve dar istisnaya bozuk/eski/yanlış metadata ile
girilememesini doğrular.

Windows Store `python` alias çalışmadığı için mevcut Codex bundled Python
çalıştırıldı. İlk sandbox denemesinde geçici dosya erişimi tamamlanamadı;
yalnız sahip olunan test süreci durdurulup aynı unit komutu dar yükseltilmiş
izinle yeniden çalıştırıldı ve12 test geçti. Paylaşılan servis durdurulmadı.

Gerçek çalışan API kesintisi/toparlanması, en fazla iki izinli TEST e-postası,
sağlayıcı teslim durumu ve yetkili posta kutusu gözlemi ana ajanın ayrı kabul
kaydıdır. Bu incelemede gerçek e-posta gönderilmedi. Aynı makinede izleyici
çalışması host/elektrik/ağ kesintisinin dış gözlem kabulü değildir.

## Son CI bağlantısı ve migration regresyonu

Ana ajanın dar isteğiyle `.github/workflows/backend-quality.yml` içine
`python3 -m unittest discover -s scripts/monitoring -p 'test_*.py' -v` adımı
eklendi. Önceki provenance, Gradle wrapper, gerçek entegrasyon önkoşulları,
backend testleri, marketplace/BAND rapor denetimleri, push migration kontrolü,
artifact ve permissions ayarları korunur. Ana ajan üç satırlık diff'i bağımsız
okudu; uygun buldu. Bu bağlantı GitHub CI çalıştı veya geçti demek değildir.

Mevcut `scripts/verify-push-migrations.ps1` önce bütünüyle ve çağırdığı dev.ps1
helper'larıyla okundu. `.env` veya launcher startup/Compose bloğu çalıştırılmaz;
AST'ten yalnız migration yardımcıları ve registry alınır. Yerel Docker'da yeni
etiketli, network-none, portsuz ve tmpfs PostgreSQL açılır. Ortak DB/Redis/Rabbit
ve ürün verisi kullanılmaz. Yıkıcı olumsuz testler yalnız bu fixture içindedir;
kapanış container ID/ad/label ve geçici klasör kök/isim denetimiyle sınırlıdır.

`pwsh -NoProfile -File scripts/verify-push-migrations.ps1` dar yükseltilmiş Docker
erişimiyle **19 PostgreSQL senaryosu,307 assertion PASS; exit0** verdi. Geçici
PostgreSQL kaldırıldı; kalıcı fixture verisi kalmadı. Tekrarlı migration'da
cihaz/mail kayıtları ve constraint/marker korunumu, eski sürümden ileri upgrade,
eski capability yeniden uygulamasında rollback ve bozuk şemada güvenli ret
doğrulandı. Betik ve tarihli SQL dosyaları değiştirilmedi.

Önemli kapsam: bu betik registry'yi mevcut15 push/source/application-mail SQL
dosyasıyla sınırlar. `scripts/dev.ps1` içine eklenmiş iki8 Ekim migration'ını
çalıştırmaz; sonucu onların execution kabulü diye sunulmaz. Yeni iki SQL'in
kendi PostgreSQL/rollout kanıtları ayrı kayıtlardadır.

Log: çalışma alanı `artifacts/ready-for-prod-01-20261008/ci-migrations/verify-push-migrations.log`.
Commit/push veya yeni geniş test koşusu yapılmadı.
