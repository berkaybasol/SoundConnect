# 01-C — mobil hata raporu ve yönetici son kayıtlar

8 Ekim 2026. FE başlangıç `f68140a8`, branch `ready-for-prod-01`. Uygulama/test
kanıtı aşağıdadır; emülatör → gerçek API/DB → yönetici ekranı ve insan görsel
kabulü ana ajan tarafından ayrıca kaydedilecektir.

## Taşıma kararı ve mahremiyet

İlk Crashlytics taslağı kaynak incelemesinde değiştirilmiştir. Runtime collection
override'ı kalıcıdır ve manifest değerinden önceliklidir; kapalı collection daha
sonra açılırsa yerelde birikmiş raporlar da gönderilir. SDK'nın otomatik native
fatal raporları, Dart hata mesajı temizleyicisinden geçmez. Bu nedenle yalnız Dart
seam'inin temizlenmesi tüm native raporların içeriği için güvence vermez.
[Firebase Android resmî sözleşmesi](https://firebase.google.com/docs/reference/android/com/google/firebase/crashlytics/FirebaseCrashlytics),
[Flutter resmî davranış](https://firebase.google.com/docs/crashlytics/flutter/customize-crash-reports).

Mevcut SDK kamu API'si ihtiyaç duyulan gönderim öncesi tüm payload filtresini
sunmadığından, mevcut `AppDiagnostics` ile mevcut authenticated API/session
altyapısı kullanıldı. Yeni BE collector küçük, sınırlı ve kendi API'sinin parçası.
Bu oturumda eklenen Crashlytics paket/plugin/manifest ve ortak Firebase başlangıç
taslağı kaldırıldı; önceki push başlangıcı aynen korundu. Pubspec/lock, Android
settings/manifest ve Firebase push provider başlangıçla aynı. Kullanıcının Firebase
ayarları değiştirilmedi; Analytics SDK eklenmedi veya collection açılmadı.

`POST /api/v1/diagnostics/mobile` body yalnız eventId UUIDv4, ERROR/FATAL, yedi sabit
source, yirmi sabit errorType, local/staging/production ve en çok kırk kaynak frame
içerir. Frame yalnız uygulamanın package kaynağı veya Dart SDK `.dart:line:column`
konumudur; method adı, absolute path, URL, argument, serbest mesaj aktarılmaz.
Ham exception, token, kullanıcı/cihaz kimliği, e-posta, mesaj ve medya içeriği yok.
Custom/bozuk stack trace exception'ı da raporlamayı bozmaz. Server aynı enum ve
formatı, 12KB toplam sınırı ve strict JSON sözleşmesini ayrıca doğrular.

Native process fatal/ANR/NDK, yan isolate ve login öncesi/guest hataları bu v1'de
toplanmış sayılmaz. Flutter framework, mevcut Bloc seam, unhandled zone ve root
isolate PlatformDispatcher gözlenir. Önceki framework/platform error handler'ın
kararı korunur. Fiziksel performans veya tüm crash coverage iddiası yok.

## Kaynak, ortam ve session sınırları

- Varsayılan kapalı derleme bayrağı `SOUNDCONNECT_DIAGNOSTICS_ENABLED`; ortam
  açıkça local/staging/production olmalıdır. Debug + production reddedilir.
  Preview ve native bridge harness diagnostics derlemeleri Gradle'da reddedilir.
- En çok 4 pending işlem; aynı source/type/severity dakikada bir ve toplam 20
  yeni olay/dakika. Aynı eventId ile yalnız bir transient retry yapılır; slot
  gerçek future bitene kadar tutulur. Bir olay toplam en çok iki kez denenir;
  sayaç penceresi değişiminde de en çok dört pending işlem sınırı korunur.
  Server Retry-After 30 saniyeyi aşarsa erken retry yerine olay bırakılır.
- Kalıcı cihaz kuyruğu yok; logout/hesap değişimi/aynı hesap relogin ve credential
  intent değişimi eski olayı yeni kimliğe taşımaz. Guest/aktif olmayan/pending
  listener-choice olayları biriktirilmeden bırakılır.
- `ready`, taşımanın yapılandırıldığı anlamına gelir. Deneme kontrolü yalnız
  exact eventId + `accepted=true` API receipt ve aynı session ile başarılıdır.
  Server/cihaz kopukken teslim edilmiş sayılmaz.
- Yavaş ekran sinyali bir dakikada en az 120 frame ve en az %5 frame'de build
  veya raster süresi 32ms üstünde olduğunda oluşur. Bu basit sinyal üretim cihaz
  kapasite/performans SLA'sı değildir; sayaç dışarıya runtime içerik taşımaz.

## Bağlı credential-intent yarışı

Gerçek Dio bellek adapter testi önce RED gösterdi: secure storage token okuması
beklerken credentialRevision değişmiş fakat eski token henüz aynıyken bir istek
dispatch oluyordu (beklenen 0, gerçek 1). Reporter sonuçta reddetse de mutation
gönderimini önleyemiyordu. Yeni optional `ApiRequestContext.expectedCredentialRevision`
token-await sonrası dispatch'ten önce ve response/error tarafında doğrulanır.
Eski 401/1308 yeni credential intent'ini değiştiremez. Bu bağlamı kullanmayan
isteklerin mevcut davranışı korunur. Preview adapter aynı optional fence'i
destekler; desteklemeyen base API context'i sessizce düşürmez.

## Yönetici görünümü

Sağlık sayfası pasif GET ile en fazla son 10 mobil rapor özetini gösterir; serbest
hata mesajı/stack ve kimlik ekrana getirilmez. Liste ve sağlık snapshot'ı aynı
bounded refresh sırasında alınır. Ekran arka planda veya görünmezken polling
yapılmaz; mevcut 5 saniyelik ekran saati ve son deneme aralığı korundu. Geç gelen
liste session/permission/generation değişiminde atılır; yetki kaybında temizlenir.
Yeni rapor/son rapor yaşı, tüketici ve provider kabul metrik etiketleri eklendi.
Provider kabulü cihaz teslimi olarak adlandırılmadı.

## Otomatik doğrulama

Son seçili koşu **80 PASS**: yeni collector/gerçek Dio yarış ve geç response
testleri, AppDiagnostics/platform handler, yönetici sağlık/son hata listesi,
mevcut push provider, preview isolation ve Dio transport regresyonları.
`artifacts/ready-for-prod-01-20261008/mobile/flutter-selected-tests.log` ile RED
kanıtı aynı klasörde korunur. Son 11 hedefi kapsayan Flutter statik analizi temiz;
`flutter-analyze.log` aynı klasördedir. `git diff --check` hata vermedi.

İlk genişletilmiş sağlık testi fake'i yeni credentialRevision property'yi
uygulamadığı için başarısızdı; fake gerçek session değişim sayacına uyarlandı.
Yeni kart sonrası eski widget assertion için gerçek kaydırma eklendi; hata
kontrolü veya güvenlik beklentisi kaldırılmadı.

Ortak API bağlamı değişimi için ardından bütün mevcut Flutter testleri çalıştırıldı:
`flutter test --no-pub` **7123 PASS, 0 fail, 2 skip, exit0** (6 dakika20 saniye).
İki skip, outputPath verilmediğinde zaten atlanan
`listener_publication_palette_visual_test.dart` ve
`listener_table_group_composer_visual_test.dart` görsel çıktı testleridir; yeni
skip eklenmedi. Son sonuç ve skip gerekçesi
`artifacts/ready-for-prod-01-20261008/mobile/flutter-full-test-summary.json`
dosyasında. Ardından `flutter analyze --no-pub` bütün kaynaklarda **No issues
found, exit0** (87,1 saniye) verdi; `flutter-full-analyze.log` aynı klasördedir.

## Manuel kabul

APK build/kurulum, emülatör deneme raporu → gerçek API receipt → DB durable kayıt
→ admin son 10 listesi, kontrollü API arızası ve yeniden deneme henüz bu alt
kaydın PASS iddiası değildir. Ana runtime kabul kaydında ayrı tamamlanacaktır.
Fiziksel Vivo bu oturum kullanıcı kararıyla zorunlu değil; emülatör kanıtı fiziksel
kabul olarak adlandırılmaz. İnsan görsel onayı ayrıca beklenir.
