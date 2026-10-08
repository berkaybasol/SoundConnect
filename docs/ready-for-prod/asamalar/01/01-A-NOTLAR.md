# 01-A — parola sıfırlama ve oturum iptali

8 Ekim 2026; uygulama, hedefli otomatik doğrulama ve izole gerçek HTTP/DB/realtime kabulü tamamlandı; yerel paket/emülatör kabulü root'ta sürüyor. Kaynak başlangıcı backend
`c537c27ce512d0c0c05063092a898c8bb5383da5`, branch `ready-for-prod-01`.

## Yeniden üretim ve çözüm

Eski kaynakta gerçek imzalı JWT, hesap parolası değiştikten sonra HTTP
principal oluşturmaya devam etti. `PasswordResetSessionRevocationTest`
başlangıç koşusu 1 test / 1 beklenen FAIL; XML
`artifacts/ready-for-prod-01/backend/01-a/baseline/` altında korundu.

Mevcut User/JWT/HTTP/STOMP altyapısı geliştirildi. `tbl_user.session_version`
başlangıçta sıfırdır. JWT normal ve kısıtlı başvuru oturumunda doğrulanan
parolayla aynı User snapshot'ındaki sürüm taşınır. Giriş, parola kontrolünden
sonra sürümü yeniden okuyup eski parolaya yeni sürüm vermemelidir.

Reset mevcut atomik OTP tüketimini korur. Kullanıcı satırı kilitlendikten sonra
ilk lookup'ın persistence-context snapshot'ı `refresh` ile yenilenir; kimlik
kontrolleri tekrar çalışır. Parola ve sürüm tek SQL UPDATE ile değişir;
artış DB'deki son değerden yapılır. Transaction rollback ikisini de geri alır.
ORM sürümü güncelleyemez; User dynamic update, önceden yüklenmiş hesapta
ilgisiz profil değişiminin eski parolayı geri yazmasını engeller.

HTTP güncel sürüm eşleşmesini zorunlu tutar; iptal edilmiş oturum mevcut 401
JSON ve `Cache-Control: no-store` sözleşmesini kullanır. Pending venue kapsamı,
hesap/rol ve listener seçim sınırları korunur. STOMP CONNECT, SEND, SUBSCRIBE
ve her outbound MESSAGE, kayıtlı bağlantının sürümünü güncel hesapla karşılaştırır.
Eski socket'e veri düşürülür ve session registry kaydı kaldırılır; yeni girişin
bağlantısı çalışır. Bu, idle fiziksel TCP bağlantısının anında kapanması iddiası
değildir; eski bağlantının ürün verisi/mutation etkisi kesilir.

İncelemede bağlı bir token-yenileme yarışı da bulundu: onaylı başvurunun
`/promote` yolu HTTP kabulünden sonra fresh User'dan token üretiyordu. Controller
artık authenticated principal'ın sürümünü kilitli promote işlemine taşır;
reset araya girdiyse yeni token üretmeden 401 verir. Güncel başvuru/rol
kontrolleri ve rota sözleşmesi korunur.

Bağlı yönetici parola değiştirme yolu da 01-C sahibi tarafından aynı
`resetPasswordAndRevokeSessions` SQL'ine geçirildi. Bağımsız dar kod incelemesinde
actor/target kilidi, lock sonrası refresh, parola dışı DTO alanlarının flush'ı,
atomik parola/sürüm SQL'i ve son refresh sırası uygun bulundu; P0/P1/P2 bulgu
kalmadı. Önceden HTTP kabul edilmiş admin mutation için aşağıdaki aynı commit
sınırı geçerlidir. Bu inceleme 01-C'nin ayrı PostgreSQL regresyon sonucunu veya
gerçek admin UI kabulünü tekrar çalıştırmış sayılmaz.

## Geçiş ve eşzamanlılık sınırı

`scripts/db/2026-10-08-account-session-version.sql` additive ve tekrar çalışabilir.
Eski kullanıcılar sıfır; rerun mevcut pozitif sürümü değiştirmez. Claimsiz
legacy JWT sıfır kabul edilir ve ilk başarılı resetten sonra reddedilir.
Yeni istemci/refresh-token sistemi gerekmez. Yerel migration listesine eklendi;
ortak DB'ye bu alt görev tarafından uygulanmadı.

Şema yeni API öncesinde uygulanır. Eski API node'ları bu kontrolü yapmaz;
mixed eski/yeni node trafiği sürerken tam iptal var denemez. Bütün auth/realtime
serving node'ları yeni sürüme alınmadan bu güvenlik özelliği aktif kabul edilmez.
Geri dönüşte eski binary kontrolü kaybettirir; rollout tercihi canlı dağıtım
yetkisi değildir.

Reset commit'i sonrasındaki yeni HTTP/WS yetkilendirmeleri eski sürümü reddeder.
Daha önce yetkilendirilip çalışmaya başlamış istek veya outbound frame geri
alınmaz. Genel request transaction/lock/refactor eklenmedi; bu sınır kullanıcıya
tam retroaktif iptal diye sunulmaz. İstemci eski oturum yanıtlarını kendi session
fence'i ile yeni oturuma taşımamalıdır (frontend doğrulaması root kapsamındadır).

## Doğrulama

Son hedefli koşu: **22 suite / 245 test, 245 PASS / 0 FAIL / 0 ERROR / 0 SKIP**;
Gradle `BUILD SUCCESSFUL`, 54 saniye. XML ve makine okunur özet
`artifacts/ready-for-prod-01/backend/01-a/final-run/`, log
`artifacts/ready-for-prod-01/backend/01-a/targeted-tests.log`.

İlk hedefli koşu: 21 suite / 221 test, 220 PASS / 1 FAIL / 0 SKIP.
Yeni concurrency fixture'ının Mockito restub'ı önceki Answer'ı null argümanla
çağırdığı için bir test durdu; `doAnswer` ile düzeltildi ve son koşuda geçti.
XML ve loglar `artifacts/ready-for-prod-01/backend/01-a/first-run/` ve
`targeted-attempt2.log` altında. Ondan önceki derleme denemesi eşzamanlı 01-C
testinin SimpleMeterRegistry AutoCloseable uyumsuzluğunda durmuştu; C sahibi
düzeltti, `targeted-compile-attempt1.log` korundu.

Kapsam: `auth.security.*`, `auth.passwordreset.service.*`,
`AuthServiceSecurityTest`, `AuthServiceVenueApplicationSessionTest`,
`shared.realtime.*`, `VenueApplicationSessionServiceTest`.
Yeni gerçek disposable PostgreSQL testi 9/9; JWT revocation 10/10;
WS interceptor 15/15; başvuru oturum servisi 23/23. Mevcut Redis freshness
testleri de bu koşuda çalıştı; Docker yokluğu nedeniyle atlama olmadı.
Komut: `gradlew.bat --offline --no-daemon --max-workers=2 test` ve yukarıdaki
sınıf/package `--tests` filtreleri. Gradle cache erişimi nedeniyle aynı komut
dar yükseltilmiş izinle çalıştırıldı; ortam, secret veya ortak servis değiştirilmedi.
`git diff --check` temiz. JAR/APK build bu alt görevde çalıştırılmadı.

Yeni kontroller: çok cihaz/replay, legacy geçiş,
bozuk sürüm claim'i, pending token, açık WS inbound/outbound, reset rollback,
commit görünürlüğü, iki eşzamanlı reset, login/reset yarışı, lookup sonrası
kimlik değişiminin kilit altında yenilenmesi ve stale profil yazımı.

## Manuel kabul

`AuthResetRealtimeAuthenticatedHttpIT` gerçek loopback HTTP listener açtı;
aynı fixture'ın sahipli PostgreSQL 16.4, Redis 7.2.5 ve STOMP eklentili RabbitMQ
3.13.7 container'larıyla **2/2 PASS, 0 SKIP**. Gradle BUILD SUCCESSFUL 1 dakika
44 saniye; senaryoların toplam süresi 6,245 saniye. Bu, test aracıyla çalıştırılan
gerçek TCP/API/kalıcılık kabulüdür; standart yerel JAR veya cihaz kabulü değildir.
XML/özet `artifacts/ready-for-prod-01/backend/01-a/http-realtime-run/`, log
`artifacts/ready-for-prod-01/backend/01-a/http-realtime-acceptance.log`.

Gerçek production auth filter/service, BCrypt, OTP/limiter Lua, JPA transaction,
mail producer, kullanıcı controller/service ve STOMP relay kullanıldı. Test
fixture'ında kayıt/profile/delete gibi çağrılmayan bağımlılıklar mock'tur;
üretim uygulamasının tamamı boot edildi denmez. Mail OTP'si gerçek Rabbit kuyruğuna
confirmed publish ile yazıldı ve yalnız fixture kuyruğundan okundu; dışarı mail
gönderen consumer/provider yüklenmedi. Hesap, token, OTP ve mail gövdesi kanıta
yazdırılmadı. Ortak servis veya kullanıcı DB verisi değiştirilmedi.

Gözlenen sonuçlar:

- İki ayrı HTTP login ve açık iki gerçek Rabbit STOMP subscriber resetten önce
  aynı badge event'ini aldı. Forgot/reset HTTP 200; DB'de version 1 ve yeni BCrypt
  parola kalıcı. Eski iki token ile gerçek username mutation isteği tekrarlar
  dahil 401; username değişmedi. Aynı OTP tekrarı 400 ve version 1 kaldı; eski
  parolayla login 401, yeni parolayla login 200.
- Reset sonrası gerçek Rabbit event'i eski iki subscriber'a outbound filter'da
  düşürüldü, yeni login'in subscriber'ı aldı. Yeni bearer ile gerçek username
  mutation 200 ve PostgreSQL'de yeni değer gözlendi.
- Label/ID ile sahipliği doğrulanmış yalnız fixture Redis'i pause edildi.
  Login/forgot/reset gerçek HTTP 503, güvenli hata kodu ve Retry-After 5; üç istek
  toplamı 8 saniyenin altında. Ayrı fault placement ile gerçek başarılı IP
  kontrolünden sonra Redis durduruldu ve account guard da 503 verdi. Her iki
  durumda unpause sonrası login 200; kullanıcı sürümü değişmedi.

Komut: `gradlew.bat --offline --no-daemon --max-workers=2 test --tests '*AuthResetRealtimeAuthenticatedHttpIT'`.
Sahipli Testcontainers kapanışta kendi kaynaklarını kapattı. JAR/APK kimliği ve
yerel ortam migration/rollout doğrulaması root'ta ayrıca belirlenecek.

Root izleme alarmı kabulü için fixture'a normalde kapalı `READY01_MONITOR_HOLD=true`
opt-in modu eklendi. Testler sonrası en fazla 600 saniye aynı gerçek HTTP API'yi
açık tutar. Gerçek health controller/service/probe-cache yalnız fixture'ın sahipli
DB/Redis/Rabbit/realtime ölçümlerini kullanır; diğer üretim bağımlılıkları UP'a
çevrilmez, bu fixture seçiminde bulunmaz. Seçim yalnız test kaynaklarındadır.
Git dışı `tmp/ready-for-prod-01/private/live-http.json` geçici admin bearer ve
loopback adresini tutar; konsola credential yazılmaz ve kapanışta descriptor
silinir. `live-http.command` yalnız `stop`, `start`, `finish` kabul eder;
`live-http-state.json` sırları içermeyen aşama kanıtıdır. Kontrol yalnız fixture
Tomcat HTTP connector'ını aynı portta durdurup başlatır; tüm process/host ölümü
veya shared API arızası değildir. Bu hook'un eklenmesi tek başına alarm/mail
kabulü sayılmaz; root gerçek çalıştırma sonucunu ayrıca kaydeder.

İlk hook koşusu tamamlandı: aynı A+B senaryoları tekrar **2/2 PASS, 0 SKIP**;
Gradle BUILD SUCCESSFUL 4 dakika 52 saniye (senaryolar 10,355 saniye,
monitor bekleme/ortam başlangıç-kapanış süresi ayrıca). İkinci kez çalıştırılan
aynı iki test toplam kapsamı iki artırmaz. XML/özet ve sırsız holder state
`artifacts/ready-for-prod-01/backend/01-a/http-monitor-run/`, log
`artifacts/ready-for-prod-01/backend/01-a/http-monitor-holder.log`.
Gerçek health cache dört sahipli bağımlılığı UP ölçtü; gerçek admin HTTP GET 200
sonrasında READY yayımlandı. Root'un harici monitor kabulü gerçek
UP → connector STOPPED → ardışık 3 DOWN tespitini doğruladı. İlk FIRING mail
isteğini dış sağlayıcı HTTP 403 ile reddetti; 202 kabulü/mail teslimi yok,
ikinci mail gönderilmedi. Root finally `finish` gönderdi; hook FINISHED oldu,
geçici token descriptor silindi ve Testcontainers kapandı. **Kesinti tespiti
PASS; dış mail kabul/teslimi AÇIK; recovery alarmı bu koşuda YAPILMADI.**
Provider teşhisi ve sonraki karar root kapsamındadır.

Sonraki ikinci alarm kabulü ve bağlı API startup düzeltmesi ayrı root/01-C
kayıtlarındadır; yukarıdaki ilk denemenin 403 sonucu sonraki denemeye taşınmaz.
Startup düzeltmesi için `01-C-STARTUP-NOTLAR.md` kaydı tutuldu.

Bu backend alt kontrolü telefon gerektirmez. Sonraki mobil kabul aşağıda ayrı
kaydedildi. İnsan görsel onayı ve fiziksel Vivo kabulü türetilmez. Commit/push
yapılmadı.

## Gerçek son APK ile mobil oturum kabulü — 8 Ekim 2026

Kullanıcının bu oturumdaki emülatör kararıyla **PASS**. Son paket
`artifacts/ready-for-prod-01-20261008/frontend/ready01-final03-debug.apk`,
SHA-256 `8122a81c10d8fc272a342faf1a61111eb806a9101dc8888abcbeef2bf0e67b34`
(225026836 bayt). Buradaki artefakt yolları çalışma alanı köküne göredir.
Root tarafından kurulan ve cihazla eşleşmesi doğrulanan aynı paket,
`emulator-5554` üzerinde yalnız yeni, geçici Android kullanıcı 10 için
`install-existing` ile etkinleştirildi. Ana kullanıcı 0'ın uygulama verisi,
Firebase kurulumu ve oturumu kopyalanmadı, dışa aktarılmadı veya temizlenmedi.

Gerçek yerel API/DB'de yalnız bir sahipli `rfp01_auth_*` test hesabı, mevcut
müzisyen rolüne bir bağ ve bir müzisyen profili oluşturuldu. Güçlü eski/yeni
parolalar ve token yalnız Git dışı özel dosyalarda tutuldu. Forgot endpoint'i
çağrılmadı, dış mail gönderilmedi; yalnız bu hesabın kısa ömürlü Redis OTP anahtarı
seed edilerek gerçek reset endpoint'i kullanıldı.

Gözlenen gerçek kullanıcı yolu:

1. Eski parola ile APK'nın normal giriş formundan giriş; sentetik kendi profil
   ekranı açıldı.
2. Oturum açıkken gerçek reset HTTP 200; eski bearer ile korumalı unread-count
   GET 401 ve eski parola ile login 401. DB oturum sürümü 0'dan 1'e çıktı.
3. Eski mobil oturumdan Mesajlar açıldı; uygulama kendiliğinden temiz giriş
   ekranına yöneldi. Elle çıkış, force-stop veya uygulama verisi temizliği yok.
4. Yeni parola ile normal APK girişi başarılı; korumalı Mesajlar ekranı
   hesabın boş konuşma listesini gösterdi. Ayrı gerçek API yeni login ve
   korumalı GET de 200 verdi.
5. Ana Android kullanıcı 0'a dönüldü. Önceki yönetici oturumu Sistem sağlığı
   ekranında güncel ölçüm zamanı ve sunucu rapor listesiyle korundu. Geçici
   ephemeral kullanıcı 10 OS tarafından kaldırıldı; son kullanıcı listesinde
   yalnız kullanıcı 0 running kaldı.

Son salt okunur ID+satır-hash karşılaştırması mevcut **36 kullanıcı, 9 rol,
35 rol bağı, 16 müzisyen profili, 540 bildirim, 105 kampanya, 99 kampanya
çalışması ve 190 alıcı satırının** değişmediğini doğruladı. Toplam kullanıcı
37; yalnız sahipli test hesabı/profili/rol bağı tutuldu, silinmedi. Parola ve
adres açık kanıtta bulunmaz. İlk izole ikinci emülatör denemesi instance
açılmadan reddedildi; daha fazla kaynak isteyen alternatif uygulanmadı ve
kabul ayrı Android kullanıcıyla tamamlandı. Primary AVD veya ortak servis
yeniden başlatılmadı.

Sırsız sonuç ve HTTP kanıtları:
`artifacts/ready-for-prod-01-20261008/mobile-auth/result.json`,
`api-reset.json`, `api-login-new.json`. Stabil gerçek ekran kanıtları aynı
klasörde `new-login-username.png` (reset sonrası giriş, parola alanı boş),
`new-session-messages-stable.png` (yeni oturumun korumalı ekranı) ve
`primary-user0-return.png`. İlk `revoked-session-result.png` geçiş animasyonunu
yakaladı; sonradan toplanan XML giriş ekranıdır, stabil görsel için yukarıdaki
login PNG kullanılır. Yeni Android kullanıcısının Gboard stylus tanıtımı
kapatıldı; ürün kodu değişikliği gerekmedi. Bu kabul insan görsel onayı veya
fiziksel Vivo kabulü değildir.
