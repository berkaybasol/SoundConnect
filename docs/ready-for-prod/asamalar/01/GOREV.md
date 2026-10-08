# Aşama 01 — hesap güvenliği ve sağlık/uyarı temeli

Durum: **KAPANDI — TANIMLI 01 V1 KAPSAMI.** Ürün geliştirmesi iki repoda
`ready-for-prod-01` üzerinde yürütüldü. Güncel yayın ve sıradaki adım
[DURUM](../../DURUM.md), işlem günlüğü [CALISMA](CALISMA.md), yetki/sıra
[PLAN](../../PLAN.md) içindedir.

## Amaç ve kalite

Şifre sıfırlama sonrası eski oturumları kesen, auth limiter arızasında güvenliği
koruyan ve sunucu/kuyruk/medya/mobil sorunlarını görünür kılıp kullanıcıya uyarı
ulaştıran ilk operasyon temeli. Üretime hazır kod kalitesi hedefle: doğruluk,
yetki/mahremiyet, eşzamanlılık, veri tutarlılığı, hata/retry ve kaynak sınırları.
Önce mevcut altyapıyı tara; uygun yapıyı kullan/geliştir. Kontrolleri gevşetme.

## Başlangıç

AGENTS ve BASLA okuma sırasını izle. İki repo HEAD/diff/staging/untracked durumunu
kaydet; hazırlık belgelerini koru. Önce kaynak bulgularını yeniden doğrula ve
anlamlı davranış testleriyle göster; rapor ifadesini test sonucu sayma.
Mevcut sözleşmeyi aşan bir mimariyi (örneğin tam refresh-token sistemi) gereksiz
yere ekleme. Gereken migration için eski veri/istemci uyumunu açıkça tasarla.

## 01-A — şifre sıfırlama ve eski oturumlar

Başlangıç kaynak bulgusu: `auth/passwordreset/service/PasswordResetService.java`
127-132 yalnız şifre hash'ini değiştiriyor. `auth/security/JwtTokenProvider.java`
ve `JwtAuthenticationFilter.java` güncel hesap/rol denetlese de şifre değişim
zamanı/token sürümü üzerinden eski tokenı reddetmiyor. P1 önceliği, canlı exploit
deneyi değil kaynak bulgusudur.

Mevcut auth/session/realtime yapısında en küçük dayanıklı çözümü geliştir.
Başarılı resetten önce verilmiş oturumların HTTP ve açık realtime üzerindeki
etkisini kapat; yeni normal giriş çalışsın. Kullanıcının uygulamasında eski
oturuma ait gecikmiş veri/mutation yeni hesaba veya yeni oturuma taşınmasın.
Reset başarısızsa yanlış oturum iptali olmamalı. Birden çok cihaz, aynı anda
reset/giriş/istek, eski-token tekrar kullanımı ve commit sınırını sınayan
testler gereklidir. Güncel hesap/rol, pending başvuru ve listener seçim
korumaları korunur. Eski token geçiş stratejisini varsaymadan belgeleyip uygula.

## 01-B — auth limiter kesintisi

`auth/ratelimit/AuthRateLimiter.java` 76-88 Redis exception'ında permit döndürüyor;
`AuthRateLimiterTest` bunu fail-open olarak bekliyor. Hem IP hem hesap guard'ının
kullanımını ve prod wiring/readiness ilişkisini incele. Redis kesintisi ve
geçersiz/boş limiter yanıtında koruma bypass edilmemeli. Güvenli failure davranışı,
anlaşılır istemci hatası ve bounded retry/Retry-After sözleşmesini belirle.
Sağlıklı normale dönüşü, farklı endpoint/hesap/IP boyutlarını, bilgi sızdırmama ve
bağlantı kaynaklarını doğrula. Testleri yeni güvenli sözleşmeye göre değiştir;
başarısız testleri susturma. Gerçek ingress/fleet ayarları henüz doğrulanmış değil.

## 01-C — sağlık, hata toplama ve uyarı v1

Mevcut Actuator/Micrometer, outbox health'leri, push/DLQ operasyon API'leri,
kalıcı mail intent, ayrı medya worker ve Flutter `AppDiagnostics` bağlantı
noktalarını kullan. Mevcut Firebase başlangıcı push'a bağlıdır; mobil hata
toplama eklenirken ortak başlangıcı incele. Google Analytics 04'tedir.

İlk kapsam: API hata/gecikme; DB/Redis/Rabbit/realtime; kuyrukta birikme ve en eski
iş yaşı; push/mail başarısızlıkları; medya işleme/cleanup/worker/depolama sorunları;
mobil hata ve gerekli sınırlı gecikme görünürlüğü. Ölçümleri admin panelinde dar,
yetkili bir sağlık ekranında anlamlı özetle sun; panelin diğer işlevleri 02'dir.

- `UP`, `DEGRADED`, `DOWN`, `UNKNOWN`, özellik kapalı ve ölçümün eskimesini ayır.
  HTTP 200 tek başına sağlıklı değildir. İşleme başarısı ile worker'ın ayakta
  olması; provider kabulü ile gerçek cihaz teslimi birbirinin kanıtı değildir.
- Son ölçüm zamanı, gecikme/birikme ve kullanıcı etkisini göster. Kaynağı bilinmeyen
  değere sıfır veya sağlıklı yazma. Ölçüm sorgularını/cache/timeout/pagination ve
  metrik etiketlerini sınırla; monitoring ürün trafiğini tüketmesin.
- Sağlık görünümü pasif olsun. Ekran açılması queue consume, replay/retry veya
  kayıt değişikliği başlatmasın; mevcut mutation güvenlikleri korunsun.
- Ortak sunucu/bildirim hattı kesilse de bağımsız kanal uyarabilsin. Tekrarlanan
  aynı arıza mesaj yağmuruna yol açmasın; toparlanma ve ölçüm kaybı da görülsün.
  İlk normal ölçümü, alarm eşiklerini, önem seviyelerini ve gerekçelerini çalışma
  kaydına yaz; ölçülmemiş kapasite veya hizmet seviyesi vaadi verme.
- Token, mesaj/medya içeriği, e-posta/telefon veya ghost kimliğini açığa çıkaran
  veri; log/metrik/crash/alarm içine taşınmasın. Ayrıntılar mevcut admin yetkisiyle
  korunmalı; yeni paralel RBAC kurulmasın.

Hizmet markası/abonelik/harici alıcı henüz seçilmedi. Mevcut hesap ve araçları
incele; maliyetli hizmet veya dış test mesajı gerekirse hedefi ve kapsamı
somutlaştırıp kullanıcıdan gereken dar bilgiyi/izni al. Kullanıcı hesabında
ayar yapılacaksa seçenekleri sade Türkçeyle açıklayarak ilerle. Tüm işi erişim
bekliyor diye bırakma; bağımsız işleri bitir. Dış teslim eksikse tam kapanış yok.

## Manuel kabul

**8 Ekim bu oturum için güncel kullanıcı kararı:** Aşağıdaki fiziksel Vivo
gerekliliği yerine Android Studio emülatörü kullanılacak. Mobil kanıt emülatör
olarak yazılır; fiziksel kabul iddia edilmez. Yalnız gerçek donanım gerektiren
somut bir bulgu varsa ayrıca bildirilir. İnsan görsel onayı ayrı kalır.

Otomatik test, gerçek API/kalıcılık, realtime, fiziksel cihaz, insan görsel onayı
ve harici alarm teslimini ayrı başlıklarda kaydet. Her kayıtta adımlar,
beklenen/gözlenen, aktör/ortam, kaynak SHA, JAR/APK/paket ve kanıt yolu olsun.

1. Gerçek HTTP/JWT + gereken DB/Redis ile reset öncesi tokenın reset sonrası
   reddi, yeni giriş, çok cihaz ve mevcut açık realtime davranışı doğrulansın.
2. Auth limiter Redis kesintisi/iyileşmesi gerçek bağımlılıkla kontrollü izole
   ortamda görülsün. Ortak Redis/Rabbit/DB'yi arıza testi için durdurma.
3. Kontrollü arıza → doğru sağlık/eskime durumu → uyarı → düzelme bildirimi
   zinciri doğrulansın. Tam servis kesintisinde dış izleme de sınansın; erişim
   yoksa açık yazılsın. Stub'a gönderim gerçek kullanıcı alarm teslimi değildir.
4. Admin sağlık UI'ı/mobil oturum davranışı/hata toplama etkileniyorsa son normal
   APK ile fiziksel Vivo'da ilgili yol ve gerçek hata raporu doğrulansın.
   Var olan hesap/veri korunsun; kabul fixture'ları dar ve açıklanabilir olsun.
5. Değişen ekran/akışın gerçek görüntüleri kullanıcıya gösterilsin; görsel kabul
   doğrudan kullanıcı cevabıyla kaydedilsin. Eski bildirim onayından türetilmesin.

Yalnız backend olan bir alt kontrol için telefon gerekmezse nedeni
UYGULANAMAZ olarak yaz; bu, tüm aşamanın mobil kabulünü kapatmaz. Yeni tam
bildirim matrisi, iOS veya üretim dağıtımı kendiliğinden başlatılmaz.

## Koruma ve kapsam dışı

Eski dirty iş, APK/JAR/evidence, cihaz/AVD verisi ve oturumlar korunur.
Reset/clean/stash/uninstall/clear, veri kaybettiren şema değişikliği ve shared
queue/servis müdahalesi genel test yetkisinden çıkarılmaz. Mevcut yerel sahte
veri ortamı kullanılabilir; bunun güvenli bir doğrulama için uygunluğu ve güncel
durumu taze kontrol edilir. Yıkıcı kesintiler izole owned fixture ortamında yapılır.

02+ panel işlevleri, diğer profillerin hesap silmesi, kullanıcı engelleme,
Analytics, feed/sponsorluk/ödeme, repo taşıma, iOS ve canlı dağıtım kapsam dışı.
Doğrudan bağlı regresyon veya güvenlik gereği ortaya çıkarsa gerekçesiyle aynı
işte çöz; ilgisiz yeni modüle geçme. Gerçek dış engel yoksa bağlı işi tek oturumda
teşhis → düzeltme → test/build → gerçek kabul → tek teslim olarak tamamla.

## Kapanış

Kabul ve bağımsız inceleme sonrası PLAN'daki mevcut commit/push/normal merge
yetkisiyle PR/ana dal CI, kaynak ve canlı uzak ref kontrolünü tamamla; gerçek
sonucu CALISMA → DURUM → BASLA → hafıza sırasıyla kaydet. Eksik kapı varken
01 tamamlandı veya ana dal kabulü var deme. Son kullanıcı kararıyla 01 v1
kapanışının ardından, 02'den önce kapsamlı observability ve PC kapalıyken alarm
için bağımsız izleme/barındırma/maliyet görüşülür. 02 görevi hazır tutulur;
görüşmeden önce 02 branch geçişi, ürün geliştirmesi veya yeni izleyici kurulumu
yapılmaz. Bu görüşme ücretli hizmet veya canlı deploy yetkisi değildir.
