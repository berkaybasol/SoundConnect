# TABLE-NOTIFICATION-TARGET-READ

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


30 Eylül 2026. Inbox-only; native TABLE/LOCAL31 desteği eklenmez.

`GET /api/v1/user/notifications/{notificationId}/table-target` exact alıcıya ait
bildirimi, kalıcı receipt ve orijinal outbox olayından alınmış kanıtla aynı SQL
snapshot'ında doğrular. Tam endpoint prefix'i `EndPoints.Notification.USER_BASE`
tarafından belirlenir. Yedi tür/action allowlist'i, masa sahibi, ilgili kişi,
aktif/doğrulanmış/silinmemiş hesaplar ve masa aktör politikası birlikte kontrol
edilir. Listener/Ghost kişisel hesaplar dışlanmaz; kurumsal rol veya kurumsal
profil ayak izi reddedilir. DTO hiçbir kişi adı/avatar snapshot'ı, joinNote,
katılımcı listesi veya sohbet döndürmez. Yetkisizlik/eksik/bozuk kaynak aynı
NOTIFICATION_NOT_FOUND sonucudur. GET read veya domain mutasyonu değildir.

## Döngü ve geçmiş kanıtı

Participant aynı kullanıcı satırında yeniden canlanır; joinedAt olaylar arasında
değişir. Kırmızı test yeni iki başvurunun ayrı kimliği olmadığını doğruladı.
Nullable `application_id` alanına her yeni join/reapply için yeni UUID yazılır;
ilgili beş başvuru/katılım olayı bu UUID'yi payload'a ekler. Mevcut satırlara
tahmini UUID/tarih eşleştirmesi yapılmaz.

Outbox normal retention ile silindiği için sadece bu kayda dayalı geçmiş hedef
kalıcı olamaz. İleri migration, özgün outbox INSERT'inden yalnız event ID, alıcı,
tür, kimlik/action/reason payload'ı ve occurredAt taşıyan
`tbl_table_notification_event` kaydını aynı transaction'da tutar. Ad, avatar,
başvuru notu veya mesaj kopyalanmaz. Mevcut outbox satırları idempotent backfill
edilir; notification veya receipt silinmez/okunmaz. Resolver notification payload'ını
bu özgün olayla birebir karşılaştırır; arbitrary payload tek başına kanıt değildir.

Trigger, hâlen çalışan eski producer/expiry worker INSERT'lerini de yakalar.
Sabit schema-qualified INSERT kullanan SECURITY DEFINER fonksiyon, eski kısıtlı
producer rolüne yeni tablo erişimi vermeden çalışır; search_path sabittir.
Eski worker collection rewrite sırasında application_id taşımayabilir. Expiry
masayı INACTIVE yaptığı için bu yalnız geçmiş sonuç yoludur, aktif hedef açmaz.
Test eski kolonlarla INSERT ve kısıtlı rolü gerçek PostgreSQL'de kapsar. Worker
paketi, outbox dispatch/lease/retry/retention ve kalıcı receipt sözleşmesi değişmez.

Legacy source kanıtı varsa olay tarihi kaynak occurredAt'tir ve geçmiş sonuç
gösterilebilir. Cycle eksikse aktif başvuru/sohbet açılmaz. Kaynak outbox kanıtı
migration öncesi kaybolmuşsa ilişki uydurulmaz: unavailable/unread. Yeni döngü
başlaması eski olayın kimliğini değiştirmez; ekranda geçmiş olay ve güncel kişinin
durumu ayrı yazılır. Güncel participant retention ile yoksa NOT_PRESENT gösterilir;
orijinal event/masa/kişiler hâlâ doğrulanmalıdır.

## Hedef ve işlem yarışı

RECEIVED: aynı application_id + aktif masa + PENDING → mevcut owner panelindeki
exact satır. APPROVED: aynı application_id + aktif masa + ACCEPTED → sohbet.
Diğer bütün doğrulanmış olaylar ve sonradan değişmiş durumlar → salt okunur RESULT.
CANCELLED yalnız kabul edilmiş alıcı/kapalı masa ve iki kayıtlı neden; EXPIRED
yalnız kabul edilmiş alıcı/INACTIVE kaynak ve özgün expiry olayı. Sürenin cihazda
geçmesi EXPIRED olayı yaratmaz. Deadline geçmiş ACTIVE kaynak aktif sohbet açmaz.

Aktif hedefte detail GET yeniden aynı kişi/cycle/status bağını kontrol eder.
Bildirimden approve/reject HTTP isteği yakalanmış applicationId ile gider;
backend aggregate lock altında, idempotent branch dahil, yeniden eşleştirir.
Eski bildirim yeni başvuruyu onaylayamaz/reddedemez. Normal masa gezinmesi ve
eski endpoint çağrıları aynı davranışta kalır.

Sohbet notification hazırlığı `markRead=false` ve APPROVED için captured
applicationId ile yetkili geçmiş GET yapar. Görünür, yüklenmiş sohbetin sonraki
frame'i ayrı normal `markRead=true` GET yapar. Bu sohbet sayacı davranışı ile
notification exact ACK ayrı işlemdir. Hata/gizli route/oturum değişimi ACK değildir.

## Flutter görünürlük ve recovery

Yedi inbox türü tek opener kullanır; eski payload route yetkisi değildir. Oturum,
notification ve route tutulur. İlk foreground bekleyişi otomatik başlayabilir;
gerçek GET hatası yalnız açık retry ile tekrarlanır. Terminal ekran olay, kaynak
tarihi, masa bağlamı, güncel masa/katılım durumu ve varsa kapanış nedenini gösterir.
Owner paneli exact kişi/cycle'ı öne alır; gerçek viewport içindeki yerleşmiş satır
ACK sebebidir. APPROVED sohbet GET hatası ACK almaz. Genel masa listesine veya
generic detail ticket'ına TABLE ACK verilemez.

Mevcut NotificationReadRecovery, hedef görünürken explicit ACK-only retry,
single-flight, route cover/pop, session ve geç cevap korumalarını sağlar. ACK
başarısına kadar optimistic sayaç güncellemesi yoktur; başarı exact bildirimi ve
ilgili cache/kart uzlaşmasını günceller. ACK retry target GET veya domain kararını
tekrar etmez. Aynı masanın ikinci bildirimi yeni ticket alır.
