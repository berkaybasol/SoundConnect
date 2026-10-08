# Özel bildirimler

7 Ekim 2026. `modules/notification/campaign`, yönetici tarafından yazılan özel
bildirimlerin bağımsız alanıdır. Promotion/announcement kaydı, akış duyurusu veya
o modülün yetkileri kullanılmaz. `ADMIN_BROADCAST` türü `CUSTOM` kategorisindedir.

## Yönetim ve zamanlama

`/api/v1/admin/notifications/campaigns` altında GET liste/detay, POST oluşturma,
PUT taslak güncelleme ve `/{id}/schedule|pause|resume|cancel` POST işlemleri bulunur.
JWT OWNER/ADMIN gerektirir; LISTENER ile karışık yönetici rolü de reddedilir.
İşlemler veritabanındaki aktif/doğrulanmış/silinmemiş hesap ve güncel rolleri
yeniden kontrol eder. Yönetici kişisel profil sahibi olmak zorunda değildir.

Oluşturmanın `requestId` UUID'si yöneticiyle birlikte tekildir; aynı gövdeyle
yinelenen istek aynı taslağı döndürür, farklı gövde 409 verir. Yazmalar
`expectedVersion` kullanır. Planlanan başlık, mesaj, hedef ve alıcı kuralı
değiştirilemez; yeni içerik için yeni taslak oluşturulur. Taslak, planlandı,
duraklatıldı, tamamlandı ve iptal edildi durumları birbirinden ayrıdır.

Başlık 120, mesaj 500 UTF-16 birimiyle sınırlıdır. Baş/son boşluklar temizlenir;
CR/LF/TAB boşluğa çevrilir. Diğer kontrol/format, bozuk surrogate ve U+2028/2029
karakterleri reddedilir. Alıcılar tüm uygun profiller, seçilmiş MUSICIAN/LISTENER/
VENUE/STUDIO türleri veya en fazla 100 seçilmiş kullanıcıdır. Mevcut kişisel profil
ile canlı rolün tutarlılığı her fanout adımında yeniden değerlendirilir.

Zamanlama UTC `startsAt` veya `localStartsAt` + IANA `zoneId` alır. İkisi birlikte
verilmişse aynı ana karşılık gelmelidir. Bitiş aynı şekilde `endsAt`/`localEndsAt`
alabilir. Tekrar ONCE, DAILY, WEEKLY (ISO 1–7) veya INTERVAL (1–365 gün) olur.
Tekrarlı planda bitiş veya 1–10000 arasında `maxOccurrences` gerekir. Yerel duvar
saati korunur; DST boşluğunda yalnız o oluşum ileri kayar, çakışmada erken offset
seçilir. Bitiş anı hariçtir. Gecikmiş aralıklar tek güncel oluşuma birleştirilir;
geçmiş bildirimleri art arda gönderme yapılmaz. Bitiş anına ulaşmış yeni alıcı
partisi dağıtılmaz; yarım kalmış oluşum da tamamlandı durumuna alınır. Bitişten
sonra devam ettirme kampanyayı yeniden planlamak yerine tamamlar. Önceki
receipt/inbox/outbox kayıtları korunur. `maxOccurrences` yeni oluşum açılmasını
sınırlar; son izinli oluşumun bitişten önceki kalan partilerini kesmez.

Her işlem en fazla 50 hesabı tarar. Scheduler her tur en fazla 10 kampanya
değerlendirir; UUID cursor başarısız kampanyanın diğerlerini engellemesini önler.
Kampanya satır kilidi aynı transaction içindeki oluşum kimliğini, alıcı cursor'ını,
receipt'i, inbox ve mevcut push outbox'ını korur. Harici ağ çağrısı içermez:
çökme/rollback kilidi bırakır ve yeniden başlatma kalıcı cursor'dan devam eder.
Oluşum+alıcı kimliği tekildir. Alıcı tavanı oluşum başlangıcındaki hesap oluşturma
zamanıdır; tarama sırasında yeni hesaplar eklenerek bitiş uzatılmaz.

Duraklatma/iptal, fanout kilidiyle sıralanır; son kontrolde artık uygun olmayan
bekleyen push işleri mevcut SUPPRESSED davranışını kullanır. Hazırlanmış veya FCM'ye
ulaşmış bir ağ gönderimi geri çağrılamaz. Duraklatma sırasında bastırılan eski
push'lar yeniden oynatılmaz; inbox kayıtları korunur. Tamamlanma veya iptal,
önceden alınan bildirimin yetkili gerçek ürün hedefine açılmasını bozmaz.

## Dağıtım ve açılış

`TransactionalNotificationService` aynı transaction içinde inbox ve
`PushDeliveryPlanner` aracılığıyla mevcut push işini oluşturur; emailForce=false.
Kaynak oluşum+alıcı ledger'ı olmayan ADMIN_BROADCAST olayı kabul edilmez. Mevcut
token şifreleme, generation/sahiplik/izin, tercih, TTL, hız sınırı ve retry kullanılır.

Android V11 capability eski aileleri de destekler. Yeni `ANDROID_CUSTOM_V1`
data-only FCM gövdesi yalnız notificationId, recipientId, type,
presentationVersion, title, body, sentAt ve expiresAt taşır. Hedef metadata/URL,
kampanya veya kullanıcı listesi taşınmaz. Native gönderimde collapse_key yoktur.
FCM ACCEPTED, cihazda gösterim veya okunma değildir.

`GET /api/v1/user/notifications/{id}/custom-target` (kısa notifications alias'ı da
vardır) sahip olunan inbox + receipt + oluşum/alıcı kanıtını doğrular. HOME,
EVENTS, TABLES, COLLAB, MARKETPLACE veya gerçek PROFILE/EVENT/CONTENT hedefi
döndürür. Profil için mevcut public resolver, etkinlik için public event guard ve
servisi, medya için mevcut readable-media guard ve mapper kullanılır. Kaybolan
veya ürün erişimi kapanan hedef yalnız sahip olunan bildirime `UNAVAILABLE`
döndürür; endpoint okundu yazmaz. Başka kullanıcının bildirimi 404'tür.

## Kurulum ve kabul sınırı

Önce `scripts/db/2026-10-07-notification-campaigns.sql` uygulanır; doğrulanmış V10
push zinciri önkoşuldur. `scripts/dev.ps1` manifestinde kayıtlıdır. Sonra
`SOUNDCONNECT_NOTIFICATION_CAMPAIGNS_ENABLED=true` açılır ve mevcut push allowed
types listesine `ADMIN_BROADCAST` eklenir. Özellik kapalı/şema yokken yönetim
işlemleri 503/1911 verir; kabul edilmiş ama çalışmayacak takvim oluşturmaz.

Otomatik testler disposable PostgreSQL'de rollback/restart, eşzamanlı worker,
cursor, tekilleştirme, rol/tercih, version ve owned hedef kanıtlarını; ayrıca DST,
metin ve serialize edilmiş FCM sözleşmesini kontrol eder. Mobil ekran/kart/açılış
etkilendiği için gerçek API/kalıcılık, fiziksel Vivo/FCM ve kullanıcı görsel onayı
ayrı kabul katmanlarıdır. Otomatik sonuçlar bunların yerine geçmez. Güncel kabul
kanıtları üst çalışma alanındaki bu işin artifact/handoff kayıtlarına yazılır.
