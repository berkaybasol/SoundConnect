# Stüdyo rezervasyon bildirimi outbox işletimi

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


24 Eylül 2026. Bu modül altı mevcut stüdyo rezervasyon olayının kalıcılığı içindir. Stüdyo başvurusu, native FCM/Flutter hedefi ve push allowlist genişletmesi kapsam dışıdır. Kodun varlığı çalışma ortamına migration veya backend kurulduğu anlamına gelmez.

## Transaction ve teslim sınırı

Rezervasyonun mevcut transaction'ında BEFORE_COMMIT dinleyicisi `tbl_studio_reservation_notification_outbox` kaydını ekler. Domain veya outbox insert başarısızsa ikisi birlikte rollback olur. Transaction olmadan bir domain event yayınlamak teslim yöntemi değildir. Kararlı eventId ve `ON CONFLICT(event_id) DO NOTHING` mevcut bir lease/başarı durumunun replay ile ezilmesini önler.

Commit sonrası sınırlı executor ilk teslimi dener; bu yalnız hızlandırmadır. İşlem kapanır veya executor dolarsa scheduler kalıcı kaydı bulur. Worker claim'i ayrı transaction'da alır; ağda publisher confirm beklerken domain transaction/oda kilidi tutulmaz.

PENDING -> IN_FLIGHT -> PUBLISHED; hata/backoff'ta yeniden PENDING, bütçe tükenince DEAD_LETTER. PUBLISHED yalnız broker'ın yayını kabul etmesidir; inbox'ın tüketildiği, websocket'in ulaştığı veya native kartın göründüğü anlamına gelmez. Ortak consumer'ın receipt/inbox dedupe ve retry/DLQ politikası ayrıca geçerlidir. ACK ile markPublished arasında kopuşta aynı eventId tekrar yayınlanabilir.

Lease sahibi ve lease bitiş zamanı terminal yazıları sınırlar. Süresi dolan worker yeni claim'in durumunu tamamlayamaz. Claim sayacı kalıcıdır; worker çöküşleri de bütçeyi tüketir. DEAD_LETTER otomatik replay edilmez.

## Explicit migration ve deploy sırası

Yeni backend başlamadan önce yetkili ortamda [stüdyo outbox ileri migration'ı](C:/Users/user/Desktop/SoundConnect/SoundConnect-Backend/scripts/db/2026-09-24-studio-reservation-notification-outbox.sql) `psql ON_ERROR_STOP=1` ile uygulanmalıdır. Mevcut ortak notification event-time/dedupe ve receipt migration'ları önkoşuldur; yeni script ortak inbox şemasını yeniden düzenlemez.

Migration tek transaction ve tekrar çalıştırılabilir. SQL'in ilk kez kurduğu tabloya da Hibernate'in daha önce oluşturduğu aynı kolonlu tabloya da 11 CHECK ve 4 index ekler. Altı type/sekiz action eşleşmesi, module=STUDIO, emailForce=false, payload object, metin sınırları, attempt/status ve lease/published tutarlılığı korunur. Hatalı eski veri varsa migration durur ve DDL geri alınır; veriyi sessizce düzeltmez veya silmez. Hibernate ddl-auto işletim migration'ının yerine sayılmaz.

Yeni tablo, hesap silmenin açık envanterinde de bulunur. Eksik tabloyu görmezden gelen cleanup eklenmedi; migration uygulanmadan yeni backend ile hesap silme denenmemelidir.

## Varsayılan ayarlar

Prefix: `app.notification.studio-reservation-outbox`. Ortam değişkeni prefix'i `SOUNDCONNECT_STUDIO_RESERVATION_NOTIFICATION_OUTBOX_`.

| Ayar | Varsayılan |
|---|---|
| batch-size | 25 |
| max-attempts | 8 |
| worker-threads / queue-capacity | 2 / 250 |
| poll-delay-ms / initial-delay-ms | 5000 / 5000 |
| lease-duration | 30s |
| retry-initial-delay / retry-max-delay | 5s / 15m |
| published-retention | 7d |
| health-undelivered-age-threshold | 30m |
| cleanup-cron | 0 50 4 * * * |

Cron'da ayrı zone verilmez; scheduler'ın sunucu zaman dilimi kullanılır. Stüdyo temizliği Collab'ın varsayılan 04:35 işinden ayrıdır.

Lease, ortak `app.messaging.notification.publisher-confirm-timeout + 1s` değerinden küçük olamaz; validator başlangıcı reddeder. Ortak confirm timeout 1–30 saniye sınırındadır; onu 30s yaparken lease'i en az31s yapmak gerekir. Retry initial <= max ve age threshold >= retry max olmalı. Bu ayarlar push TTL'i veya Rabbit consumer retry sayısını değiştirmez.

## Gözlem ve retention

Health bean: `studioReservationNotificationOutboxHealth`. PENDING, IN_FLIGHT, DEAD_LETTER sayıları ve en eski teslim edilmemiş kaydın zamanı raporlanır. DEAD_LETTER varsa veya en eski bekleyen kayıt age threshold'a ulaştıysa bileşen DEGRADED olur. Repository/DB okuması başarısızsa UNKNOWN; sıfır kuyruk/sağlıklı sonuç gibi sunulmaz. Bu durum metni tek başına actuator HTTP status mapping garantisi değildir.

Yalnız metadata okumak için:

```sql
select status, count(*) as records,
       min(created_at) as oldest_created_at,
       min(next_attempt_at) as next_attempt_at
from tbl_studio_reservation_notification_outbox
group by status
order by status;
```

Genel operasyon logları eventId/tür/deneme sayısı ve exception sınıfıyla sınırlıdır; payload, başlık, body veya exception mesajını loglama. Gecikmede broker/confirm, worker doluluğu, DB ve lease ayarları incelenir. Yeni eventId oluşturarak replay yapma; mevcut receipt dedupe fence'ini delersin. Bu belge otomatik veya izinsiz manuel replay aracı sağlamaz.

Başarılı PUBLISHED kayıtları publishedAt+retention sonrasında temizlenir. PENDING/IN_FLIGHT/DEAD_LETTER için zaman bazlı otomatik silme yoktur; teslim edilmemiş iş sessizce kaybolmaz. Bunun sonucu, operasyonel olarak çözülmeyen başarısız kaydın snapshot'ının daha uzun saklanabilmesidir. Hesap erasure temizliği dört durumun tamamına uygulanır.

## Hesap silme ve snapshot gizliliği

ListenerAccountDataCleaner recipient_id veya payload'da silinen kullanıcı UUID'si bulunan stüdyo outbox kayıtlarını silme transaction'ında kaldırır. Başka kullanıcının ilgisiz satırı korunur; late cleanup hatasında hesap ve outbox silmeleri birlikte geri alınır. Payload serbest eski ad metni taşısa bile requesterId referansı silme eşlemesini sağlar; producer'ın bu kimliği kaldırmaması gerekir.

Bütün gerçek stüdyo event üreticileri oda/rezervasyon kilidi altında çalışır. Silme prepareLifecycle işlemi aynı oda/rezervasyon kilitlerini önce alır, sonra outbox envanterini temizler. Domain önce commit olmuşsa silme snapshot'ı da kaldırır; silme önce olmuşsa mevcut source terminal hale gelir ve eski pending/confirmed olayı üretilmez. Create ayrıca requester hesabında write kilidiyle profile dönüşümünü sıralar.

Enqueue, recipient ve varsa payload requesterId hesabının mevcut/nonerased olduğunu scalar query ile yeniden denetler; silinmiş hesaba ait gecikmiş snapshot'ı eklemez. Bu sorgu yeni bloklayan account fence değildir. Tam eşzamanlılık güvencesi gerçek domain üreticilerinin aggregate kilidi sözleşmesine dayanır; keyfi internal caller'ın oda/rezervasyon kilidi olmadan eski snapshot enqueue etmesine aynı garanti verilmez. Yeni üretici eklenirken bu sözleşme test edilmelidir. Mevcut silme yalnız listener profilleri için desteklenir; stüdyo sahibinin self-erasure özelliği eklenmiş sayılmaz.

Publisher daha önce bir payload kopyası almışsa DB satırının silinmesi broker'a ulaşmış kopyayı geri çağırmaz. Ortak notification admission'daki account erasure fence erased kaynak için inbox/teslimi engeller; Rabbit üzerindeki mevcut retention/DLQ politikaları ayrı sınırdır.
