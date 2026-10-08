# 01-C backend sağlık görünümü

8 Ekim 2026. Backend uygulaması ve hedefli otomatik testleri tamamlandı. Gerçek
API/cihaz/harici alarm ve bağımsız bütünleşik kabul ayrı; aşama kapanışı değildir.

## Sözleşme

`GET /api/v1/admin/system-health`, mevcut `ADMIN_PANEL_ACCESS`, `BaseResponse.data`:
`status`, `generatedAt`, `refreshIntervalSeconds`, `staleAfterSeconds`, `components`.
Her bileşen: `id`, `label`, `status`, nullable `measuredAt` (UTC ISO-8601), nullable
`ageSeconds`, enum `reasonCode`, sabit Türkçe `userImpact`, sayısal `metrics`.
HTTP 200 yalnız isteğin başarılı olduğunu anlatır; sağlık kararı `data.status` içindedir.
`Cache-Control: no-store` ile istemci/proxy saklaması kapalıdır.

Durumlar: `UP`, `DEGRADED`, `DOWN`, `UNKNOWN`, `DISABLED`, `STALE`.
Reason code sözleşmesi kaynak `SystemHealthSnapshot.ReasonCode` enum'undadır.

Sabit kimlikler: api, database, redis, rabbit, realtime, push, mail,
notificationDlq, followOutbox, tableOutbox, collabOutbox, overthinkingOutbox,
eventOutbox, studioOutbox, venueSuggestionOutbox, mediaProcessing, mediaCleanup,
mediaWorker, storage, mobileErrors, notificationQueue, mailQueue, mailDlq,
mediaQueue, mediaDlq.

Metrikler yalnız sabit whitelist'ten gelir: pending, inFlight, deadLetter,
suppressed, retry, publishing, review, stale, providerAccepted,
oldestPendingAgeSeconds, readyMessages, consumers, failed; API için requestCount,
serverErrorCount, serverErrorRatePercent, meanLatencyMillis, windowSeconds.
Mobil için eventsLast15Minutes, fatalEventsLast15Minutes,
slowFramesLast15Minutes, lastEventAgeSeconds.
Kaynak host/queue/bucket/key/URL, exception metni, hesap/mesaj/medya içeriği
aktarılmaz. Push providerAccepted cihaz teslimi değildir. Mail kaydı broker'a
aktarımı ölçer, son alıcı teslimini ölçmez.

## Kaynaklar ve sınırlar

Mevcut Actuator HealthContributorRegistry ve Micrometer kullanılır. Yeni API
sorgusu yalnız önbelleği okur; DB/Rabbit sorgusu, consume/replay/retry başlatmaz.
Bağımsız planlı gözlem varsayılan 15 saniyede bir, dört daemon worker ve en çok
32 bekleyen görev ile çalışır. Ortak deadline iki saniyedir. Kesilmeyen kaynak
işi gerçekten dönmeden tekrar kuyruğa alınmaz; geç sonuç önbelleği güncellemez.
JDBC sorgularının timeout'u ayrı lokal JdbcTemplate'te; mevcut DB indicatorları
read-only timeout transaction içinde çalışır. Başarısız probe son ölçüm zamanını
ilerletmez. Ölçüm 60 saniyeyi geçtiğinde `STALE` olur. Hiç ölçülmeyen `UNKNOWN`.

API ölçümü process-local `http.server.requests` dönem farkıdır. İzleme çağrıları
çıkarılır; ilk örnek WARMING_UP, trafiksiz aralık NO_TRAFFIC, 512'den fazla seri
METRIC_LIMIT_EXCEEDED olur. Varsayılan en az 20 istekte ortalama >=1000 ms veya
5xx oranı >=%5 DEGRADED. Bunlar başlangıç operasyon eşikleridir, kapasite/SLA vaadi
değildir. İlk normal canlı ölçüm gerçek API kabulünde kaydedilmelidir.

Outbox eşikleri mevcut indicatorlardan gelir. Medya SELECT aggregate sorguları
yalnız durum/sayı/en eski zamanı okur. 30 dakikayı aşan bekleyen iş DEGRADED;
geçmiş FAILED toplamı tek başına sürekli arıza ilan edilmez. Silme/cleanup yaşı
durable silme/cleanup sınırından gelir; eski bir medyanın yeni silinmesi yanlış
eski-birikim alarmına dönmez. Medya worker için
mevcut readiness dosyasının salt okunur mount yolu `app.system-health.media-worker-health-file`
ile isteğe bağlı bağlanır; bağlanmamışsa UNKNOWN. Worker marker yalnız DB/Rabbit
probes başarısıdır, transcode veya storage başarısı değildir.

Storage ana ajanın eklediği mevcut S3 istemcisi üzerinden sınırlı salt okunur
HEAD kontrolüne bağlandı. Sağlayıcı bu kontrolü yetkisiz bulursa UNKNOWN;
erişim ölçümü upload/CDN başarısı sayılmaz. Mevcut veri yokken sıfır/sağlıklı
üretilmez. Notification DLQ yalnız mevcut pasif summary'yi okur. Diğer sabit
kuyruklar mevcut AmqpAdmin.getQueueInfo pasif metodunu kullanır;
consume/replay/ACK/purge yoktur, unacked/toplam/en eski yaş uydurulmaz. Başlangıç
normal kuyruk eşiği 1000 hazır mesaj; tüketici yokken boş kuyruk DEGRADED,
bekleyen mesaj varsa DOWN. DLQ'da herhangi hazır mesaj/tüketici DEGRADED.

## Mahremiyet sınırlı mobil hata alımı

Ana ajanın teknik kararı: Crashlytics SDK global native fatal yakalama kanalında
ham exception mesajını gönderimden önce güvenilir biçimde süzecek hook yok.
Görevin token/mesaj/hesap içeriğini taşımama şartı nedeniyle SDK etkinleştirilmedi;
mevcut AppDiagnostics seam'i ve mevcut JWT/HTTP/backend depolaması kullanıldı.
Analytics kapsamı açılmadı. Bu yol Dart tarafından yakalanmış güvenli hata
kodlarını toplar; native process crash yakalama veya harici alarm teslimi değildir.

`POST /api/v1/diagnostics/mobile`: normal geçerli ürün oturumu gerekir.
İstek yalnız eventId (UUIDv4), severity (ERROR/FATAL), source enum,
errorType enum, frames ve environment (local/staging/production) taşır.
Enumlar `MobileDiagnosticRequest` içindedir. Frames en fazla40 kaynak konumu,
her biri en fazla240 ASCII karakter; yalnız uygulamanın package kaynakları
ve dart SDK dosya/satır bilgisi kabul edilir. Method/class/exception mesajı,
URL, kullanıcı/kurulum/cihaz kimliği, breadcrumb veya token alanı yoktur.
Gövde en fazla12288 bayt; unknown/duplicate JSON alanı, trailing JSON ve sayısal
enum reddedilir. Hatalı body içerikleri cevap/log/DB'ye yansıtılmaz.

202 cevabı BaseResponse.data `{eventId,accepted:true}` verir. 400 geçersiz
payload/ortam; 401 session; 409 aynı eventId farklı içerik; 413 body sınırı;
429 kota; 503 kapalı/Redis/DB/kapasite için kullanılır. 429/503 Retry-After
sınırlıdır. Ortam sunucunun `app.diagnostics.mobile.environment` değerine eş
olmalıdır (varsayılan local); farklı ortam kayıtları karıştırılmaz.

Mevcut AuthRateLimiter Lua/digest altyapısı ve TrustedProxyClientAddressResolver
yeniden kullanılır. Kullanıcı başına20, IP60, global200/dakika varsayılanı var;
bu guard genel auth limiter disable ayarından bağımsız daima korumalıdır.
Redis arızası/boş geçersiz cevap fail-closed. Redis anahtarları süreli SHA-256
digest taşır; DB'ye requester kimliği veya IP yazılmaz.

Additive migration: `scripts/db/2026-10-08-mobile-diagnostics.sql`.
Schema eksikken 503/UNKNOWN; migration kendiliğinden uygulanmaz. eventId ve
kanonik içerik fingerprint'i tek kayıt/idempotency sağlar. DB aynı transaction
içinde güncel session_version/active/verified/erased guard'ını FOR SHARE ile
okur; password-reset update kilidiyle commit sırası korunur. Kimlik yalnız bu
geçici kontrolde kullanılır. Global admission advisory lock + bounded count
en fazla100000 kayıt sınırını korur. Varsayılan7 günlük retention, ayrı daemon
görevinde dakikada en fazla1000 eski diagnostic satırını siler; ürün tabloları
ve mevcut kabul verileri retention kapsamına girmez.

mobileErrors kartı son15 dakika sunucuya kabul edilen kodları sayar: en az1
FATAL veya toplam20 olay DEGRADED. Bunlar istemci tarafından bildirilen kodlar,
gerçek cihaz yakalama/teslim kabulü değildir. `GET /api/v1/admin/system-health/mobile-events?limit=10`
aynı ADMIN_PANEL_ACCESS ile en fazla10 son olayı verir:
`{events:[{eventId,receivedAt,severity,source,errorType,environment}]}`.
Frames veya kimlik çıkmaz; unknown DB değerleri raw metin olarak aktarılmaz.

## Otomatik doğrulama

- İlk sağlık paketi15/15 PASS: `artifacts/ready-for-prod-01-20261008/01-c-health-tests`.
- Son bağlı koşu32/32 PASS,0 FAIL,0 SKIP:
  `artifacts/ready-for-prod-01-20261008/01-c-collector-tests`.
  15 sağlık +13 mobil collector +4 ana ajanın S3 testleri. Komut:
  `gradlew.bat test --tests 'com.berkayb.soundconnect.modules.admin.health.*' --tests '*S3StorageHealthProbeTest' --tests '*S3StorageClientDownloadTimeoutTest' --no-daemon --console=plain`.
- Collector kabulü güçlendirme12/12 PASS,0 SKIP:
  `artifacts/ready-for-prod-01-20261008/01-c-collector-strengthened`.
  Controller7 + gerçek PostgreSQL5 test; chunked istek12KB sınırı ve session
  yarışında gerçek DB kilit beklemesi gözlenerek commit sonrası ret doğrulandı.
- Davranışlar: pasif snapshot ve queue, whitelist/mahremiyet, API dönem
  delta/no-traffic/toparlanma, timeout sırasında tekrar iş birikmemesi ve geç
  sonucun atılması, eskime/ölçüm zamanı; strict body/auth/quota/receipt;
  disposable gerçek PostgreSQL'de idempotency/conflict, session revoke/reset
  commit yarışı, kalıcı kapasite, sınırlı retention ve güncel mobil sayımlar.
- İlk H2 testinde UTC init fixture eksikliği ve bir Mockito yeniden stublama
  test kusuru görüldü, ürün güvenlik kontrolleri gevşetilmeden düzeltildi.
  İlk compile testindeki SimpleMeterRegistry kapatma fixture'ı da explicit
  finally close olarak düzeltildi. Son hedefli koşu temizdir.

## Bağlı admin şifre düzeltmesi

01-A/01-B bağımsız kaynak kontrolünde P2 bulundu: mevcut admin kullanıcı
güncelleme yolu şifreyi değiştiriyor, ancak session_version artırmadığı için
eski JWT ve bağlı WebSocket oturumu kullanılmaya devam edebiliyordu. Ana ajan
aynı oturumda bağlı düzeltmeyi yetkilendirdi. `UserServiceImpl.updateUser` artık
aktör/hedef kilidi altında güncel ORM durumunu yeniler; DTO'nun diğer alanlarını
flush ettikten sonra public resetin mevcut ortak atomik şifre/version SQL'ini
kullanır ve entity'yi yeniler. Şifre ORM save ile ayrı yazılmaz. Diğer DTO
alanları korunur, transaction rollback bütün değişimi geri alır, yalnız kimlik
alanı değişimi oturumu iptal etmez. Yeni RBAC veya iptal altyapısı eklenmedi.

- RED: eski ürün kodunda yeni gerçek PostgreSQL fixture3 testinden1'i,
  eski JWT'nin hâlâ kabul edilmesi nedeniyle beklenen biçimde FAIL verdi.
  Kanıt: `artifacts/ready-for-prod-01-20261008/01-c-admin-password-red`.
- GREEN:5 sınıfta63/63 PASS,0 FAIL,0 SKIP. Yeni admin PostgreSQL3,
  UserServiceImpl26, public reset PostgreSQL9, session revocation10,
  WebSocket interceptor15. Kanıt:
  `artifacts/ready-for-prod-01-20261008/01-c-admin-password-green`.
- Yeni test gerçek PostgreSQL işlemi sonrasında ürün JWT filter ve WebSocket
  interceptor'ını çağırır; eski JWT/eski bağlı oturum reddi, yeni JWT kabulü,
  aynı DTO'da username/email korunması ve rollback sınanır. Bu otomatik fixture
  gerçek HTTP soketi veya fiziksel cihaz kabulü olarak sayılmaz.
- Bağımsız ürün incelemesi ana ajanın diğer incelemecisinde; bu düzeltme sonrası
  kendi incelemem bağımsız kapanış kanıtı olarak kullanılmaz.

## Son paket ve yerel rollout kontrolü

- A incelemecisi admin şifre ürün düzeltmesini bağımsız kontrol etti;
  kilit/refresh/diğer alanları flush/atomik şifre-version/refresh sıralamasında
  doğrulanmış P0/P1/P2 bulgu bildirmedi.
- Root rollout betiğinin salt okunur incelemesinde kısmi docker stop hatasında
  rollback işaretinin geç atanması bulundu. Root işareti stop öncesine aldı;
  düzeltme yeniden okundu. Betik bu alt görev tarafından çalıştırılmadı.
- Private dump SHA ve ortam dosyası SHA hazırlık kaydıyla eşleşti. Image katmanı
  ve config koruma, worker image pin, iki additive SQL, kritik satır hash kontrolü
  ve DB/Redis/Rabbit ID/StartedAt koruma sınırları kaynakta doğrulandı.
- Production diagnostics ortamı ve exact Compose worker marker env bağlaması
  için B'nin son config düzeltmeleri5/5 test PASS,0 SKIP:
  `artifacts/ready-for-prod-01-20261008/01-c-final-config`.
- `gradlew.bat bootJar --no-daemon --console=plain` PASS (11 saniye).
  `build/libs/soundconnect-api.jar`:111903326 bayt;
  SHA-256 `b3a59315f143faecffb72847a26b5a8f738cde8b8b2282c36491abecf0d89919`.
  Build log/manifest: `artifacts/ready-for-prod-01-20261008/01-c-final-build`.
  Bu paket üretimi gerçek çalışan API veya cihaz kabulü değildir.
- İlk B3A paketinin gerçek startup kabulünde sağlık probe başlangıç zamanı
  kaynaklı lifecycle sorunu çıktı; A incelemecisi ApplicationReadyEvent temelli
  düzeltmeyi üstlendi. Eski derlenmiş kaynakla başlayan full suite, root isteğiyle
  fixture FINISHED sonrasında Ctrl+C ile durduruldu (exit1); tam suite PASS yok.
  `01-c-full-backend/aborted.txt` ve `partial-results-not-final` bunu gösterir;
  oradaki eski config XML'leri tam suite sonucu değildir. Son paket ve tam koşu
  düzeltme sonrasında yeniden alınacak. Alarm kabulü root kaydında ayrıdır;
  yeni full koşuda monitor holder kapalı kalacak, tekrar e-posta gönderilmeyecek.
- Lifecycle düzeltmesi A'da37/37 PASS,0 SKIP sonrasında yeni bootJar PASS:
  111903568 bayt, SHA-256
  `ed1ba65697aa9fceb1c23b0620d0087aff8cc20893ce7a4322ff8b80459b34a1`.
  `01-c-lifecycle-final-build` log/manifest; önceki B3A paketinin yerini alır.
  Tam backend regresyonu `01-c-full-backend-final` altında holder kapalı koşulur.
- Son standart tam backend koşusu tamamlandı:42m27s BUILD SUCCESSFUL,
  753 suite,6056 PASS,0 FAIL/ERROR. Ham XML119 skip kaydı vardır:
  101 parametresiz test +18 kaynakta doğrulanmış, argümanlara açılmamış
  parameterized method template. Bunlar PASS değildir. Skip gate'leri HEAD ile
  aynı:113 PostgreSQL/3 Redis opt-in,2 gerçek FFmpeg opt-in,1 önceden Disabled
  context kaydı. Mevcut analytics-load/feed-load dışlamaları korunur.
  Auth/health/collector/admin parola/WS/storage/config ilgili seçkisi56 suite,
  454 test,0 skip. `summary.json`, `suites.json`, `xml/`, `skip-gates.json`,
  `skipped-parameterized-templates.json`, `stage-relevant-suites.json` bu
  evidence klasöründedir; JAR SHA ed1ba... değişmedi. Holder kapalı, mail yok.

## Ön yüz bağımsız inceleme ve bağlı P3

Ana ajan isteğiyle health domain/repository/screen ve diagnostics/network session
fence kaynakları salt okunur incelendi. Sabit taxonomy, kaynak konumu filtresi,
en fazla4 bekleyen ve20/dakika report, aynı UUID ile bir sınırlı retry,
guest/listener-choice ret ve credential-revision kontrollerinde yeni doğrulanmış
P0/P1/P2 görülmedi. Native crash kapsamı veya fiziksel kabul iddiası kurulmadı.

P3 bulundu: bir kart ageSeconds+yerel elapsed ile STALE olurken üst gösterge
yalnız snapshot alındıktan sonraki yaşı kullandığı için Normal kalabiliyordu.
Ana ajan dar düzeltmeyi yetkilendirdi. `SystemHealthSnapshot.effectiveStatus`
artık mevcut olumsuz sunucu durumunu ve kartların güncel ölçüm durumlarını birlikte
toplar; global snapshot eskime sınırı da korunur. UI üst pill aynı hesabı kullanır.
age50+11 saniye RED testinde eski sonuç UP çıktı; düzeltme sonrası bütün ilgili
sağlık testleri12/12 PASS. UNKNOWN veya eksik ölçüm Normal'e yükseltilmez.
Kanıt: `artifacts/ready-for-prod-01-20261008/01-c-health-ui-stale`.
Bu bağlı düzeltme başka incelemeciye bağımsız review için iletildi; yeni APK ve
fiziksel/görsel kabul ana ajanda ayrıca izlenir.

Bağımsız incelemeci B, aynı ekranda ikinci bağlı P3 buldu: recent-events cevabı
beklenirken sağlık cevabının yaşı sayılmıyordu. Gerçek monoton saatli widget testi
age60 +1.1 saniye gecikmeyle RED verdi. Ekran artık istek başında açılan monoton
sayacı başarılı snapshot'a devreder; ağ ve recent-events beklemesi sayılır.
Başarısız/eskimiş oturum cevabı önceki ölçüm sayacını sıfırlamaz. Son13/13 sağlık
testi PASS;3 kapsam dosyası Flutter analyze PASS. Son loglar
`01-c-health-ui-stale/recent-delay-red.log`, `recent-delay-green.log`,
`recent-delay-analyze.log`; ilk12 testlik kaydın üstüne bu son kanıt esas alınır.
B son kaynakta monoton sayaç devri, başarısız/geçersiz cevap ve domain durum
sıralamasını bağımsız yeniden inceledi; iki P3 kapandı, yeni P0/P1/P2/P3
bildirmedi. İncelemeci kod değiştirmedi.

## Manuel kabul

- Otomatik test/build: yukarıdaki32 hedefli test PASS; test için kaynaklar derlendi.
  Paket/JAR build ve bütünleşik regresyon ana ajan kaydında ayrıca gösterilir.
- Gerçek API/kalıcılık: ana ajan tarafından yapılacak; admin izni, pasif/cache davranışı,
  UNKNOWN/eskime/düzelme ve son JAR kaynağı ayrı kaydedilecek.
- Fiziksel Vivo/kullanıcı görsel kabulü: bu backend alt işinde yapılmadı; bütün 01'in
  değişen sağlık UI'ı ve mobil davranış kabulü ana ajan kapsamındadır.
- Harici alarm teslimi: bu alt işte uygulanmadı veya kabul edilmedi; ana ajan ayrı yürütüyor.
