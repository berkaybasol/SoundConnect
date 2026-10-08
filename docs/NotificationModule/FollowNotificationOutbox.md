# Kalıcı kişi ve band takip bildirimi

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


27 Eylül 2026 — FOLLOW-DURABLE-OUTBOX. Yalnız `SOCIAL_NEW_FOLLOWER` ve
`SOCIAL_NEW_BAND_FOLLOWER` inbox üretimi. Push allowlist/capability, frontend ve APK
bu değişikliğin kapsamında değildir.

## Transaction ve kimlik

`FollowServiceImpl` ve `BandFollowServiceImpl`, ilişkiyi kaydettikleri transaction'da
`FollowNotificationOutboxService.enqueue` çağırır. Bu çağrı `MANDATORY`'dir.
Kalıcı INSERT/constraint hatası ilişkiyi de geri alır; Rabbit'a bu transaction'da
erişilmez. Başarılı follow, bildirim broker'a henüz gönderilmemiş olsa da kalıcı
intent bırakır. Bu, önceki hata yutan best-effort kayıt davranışının bilinçli değişimidir.

Occurrence, yeni ilişki satırının UUID'sidir. Event UUID'si sürümlü namespace,
occurrence, alıcı ve türden türetilir. Band alıcıları farklı UUID alır; receipt
anahtarı yalnız event UUID olduğundan tek ortak fanout UUID kullanılmaz. Yinelenen
enqueue lease/attempt/timestamp durumunu değiştirmez. Duplicate follow yeni iş
üretmez. Unfollow önceki intent'i iptal etmez; refollow yeni occurrence oluşturur.
Eski takipler backfill edilmez.

Bandın transaction içindeki ACTIVE, distinct ve follower hariç üyeleri ayrı satırlar
olarak sabitlenir. Sonradan katılan üyeler eski olaya eklenmez. Üyelikten ayrılma,
önceden kabul edilmiş takip olayının alıcı listesini yeniden hesaplatmaz.

## Gizlilik ve silme

Outbox yalnız occurrence/follower/recipient/band ID'leri, tür ve olay zamanı taşır;
isim, avatar, başlık, gövde veya eski kimlik JSON'u saklamaz. Her yayın denemesinde
tek yeni worker transaction'ında account fence, varsa band read lock ve mevcut
ghost resolver'ın visibility read lock'u kullanılır. Güncel kimlik hazırlanıp
confirmed yayın tamamlanana kadar bu kilitler tutulur. Claim transaction'ı önce
biter; sonuç işaretleme transaction'ı yayın transaction'ından sonra başlar.
Broker beklenirken nested ikinci DB bağlantısı alınmaz.

Resolver/DB hatası kimlik fallback'i üretmez; kayıtlı retry olur. Mevcut ghost
canonical username/null avatar, onboarding anonim kimliği, normal venue adı ve
consumer tarafındaki güncel kimlik yenileme kuralları korunur.

Silinmiş aktör/alıcı veya bulunmayan band yayınlanmaz: satır hâlâ mevcutsa fenced
`SUPPRESSED / SourceUnavailable` olur; `PUBLISHED` denmez. Geçici sorgu/resolver
hatası `SUPPRESSED` sayılmaz. `ListenerAccountDataCleaner` tüm durumlarda actor
ve recipient referanslarını aynı erasure transaction'ında siler; diğer alıcının
intent'i korunur. Receipt tombstone'ları mevcut sözleşmeye göre kalır.

İlişki/account/band FK'sı bilinçli olarak yoktur: ephemeral follow veya silinen
band üzerinden cascade kabul edilmiş olayı sessizce düşürmemelidir. INSERT
trigger'ı account satırlarını sıralı shared lock ile kontrol eder; erasure cleanup
sonrasında yeni intent doğmasını engeller. Fiziksel kaynak yokluğu yayında ayrıca
kontrol edilir. Önceden broker'a verilmiş mesajlarda mevcut consumer account fence
ve rehydration protokolü geçerlidir.

## İşçi, retry ve gözlem

Scheduler varsayılan 5 saniyede due işleri tarar; stüdyo outbox protokolündeki
bounded coordinator/executor kullanılır. Yerel queue'ya konurken DB claim yapılmaz;
işçi başladığında atomik UPDATE kazanır. Queue reddi veya scheduler durması satırı
silmez. Başlangıçta pending ve lease'i bitmiş işler yeniden bulunur.

Varsayılanlar: batch 25, iki işçi, queue 250, lease 30 saniye, en fazla 8 claim,
5 saniyeden 15 dakikaya üstel backoff. Worker crash/reclaim de attempt tüketir.
Lease owner ve expiry, eski işçinin success/failure/suppression yazmasını engeller.
NACK, mandatory return, timeout ve bağlantı hataları başarılı yayın değildir.
`publishConfirmed` persistent, routed ACK ister. ACK ardından DB işaretleme
başarısızsa aynı eventId yeniden yayınlanır; mevcut receipt tek inbox etkisini
korur, okundu durumunu sıfırlamaz ve silinmiş bildirimi diriltmez.

`DEAD_LETTER` ve `SUPPRESSED` otomatik temizlenmez. Yalnız 7 günden eski PUBLISHED
işler temizlenir. `followNotificationOutboxHealth` pending/inFlight/deadLetter/
suppressed sayılarını ve en eski açık iş zamanını verir. Dead letter veya 30
dakikayı aşan açık iş `DEGRADED` olur. Log/last_error_type yalnız güvenli hata
sınıfı, tür, attempt ve event kimliği taşır. Ayrı redrive paneli eklenmedi.

`app.notification.follow-outbox.*` ayarları diğer outbox sınırlarıyla uyumludur;
ortak startup validator lease >= publisher confirm timeout + 1 saniye ister.
Push enabled/allowed-types bu üretim yolunu durdurmaz. Bu iki tür mevcut LOCAL15
listesinde bulunmaz; inbox kabulü native teslim kabulü değildir.

## Kurulum ve sınır

Yeni JAR'dan önce `scripts/db/2026-09-27-follow-notification-outbox.sql` migration'ı
`psql -v ON_ERROR_STOP=1` ile uygulanmalıdır. Normal `scripts/dev.ps1` registry'si
bu repeatable adımı içerir. Beş push V3/V4 marker'ı değişmez. SQL veri silmez;
tekrar uygulama satırları korur, eksik kontroller/indeksleri kurar, uyumsuz sütun,
yanlış indeks veya geçersiz eski veri varsa transaction'ı hata ile geri alır.
Hibernate auto-ddl bu işletim adımının yerine geçmez.
