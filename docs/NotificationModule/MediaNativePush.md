# MEDIA native push — 29 Eylül 2026

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


`SOCIAL_LIKE` ve `SOCIAL_COMMENT` için yalnız `targetType=MEDIA` kapsamı.
Mevcut domain transaction → receipt/inbox → synchronous NotificationPersisted →
kalıcı push delivery bağı kullanılır. Medya kimliğinin anonim depolama, version1
actor UUID, güncel canonical/GHOST/erasure ve version0 legacy sözleşmesi korunur.

## Capability ve rollout

Yeni kayıt `ANDROID_NATIVE_V6`; yalnız V6 MEDIA alır. V1–V5 geçerlidir, V6 eski17
türün planner/prepare/presentation yollarını da destekler. Scope/preference,
permission, generation, clientRevision, TTL, recipient ve hesap kontrolleri kalır.
Capability değişimi tek başına generation artırmaz; geç registration revision ile,
V6→V5 sonrası bekleyen MEDIA ise prepare anındaki güncel capability ile reddedilir.

`2026-09-29-push-native-media-capability.sql` önceki altı capability marker'ını
ister; doğrulanmış V6 CHECK'leri ve yedinci marker aynı transaction'dadır. Eski
migration ve medya sanitizer trigger/constraint'leri değişmez.

## Kapalı wire

Yalnız yedi string alan: `notificationId`, `recipientId`, `type`,
`presentationVersion=ANDROID_MEDIA_V1`, `displayVariant=DEFAULT`, `sentAt`,
`expiresAt`. HTTP v1 data-only; isim, avatar, actor/media/comment ID, yorum metni,
medya URL'si veya credential taşımaz. Eksik/fazla alan, yanlış tür/variant/version
reddedilir. Ortak native renderer ve önceki17 görsel kaynak değişmez.

Prepare güncel MEDIA varlığı, READY/PUBLIC ve URL bulunabilirliğini; listener
MAINSTAGE/STUDIO kısıtını ve listener-owner STANDARD/choice durumunu yeniden okur.
Genel delivery policy güncel actor/recipient ve yetki kapılarını uygular.

## Native ve inbox hedef/read

İki giriş de `MediaNotificationOpenScreen` kullanır. Captured geçerli oturum,
recipient ve exact notification GET doğrulamasından sonra authorized
`GET /api/v1/user/notifications/{notificationId}/media` çağrılır. Eski payload URL
veya media ID ile fallback route açılmaz. Read ticket aynı resolver nesnesine ve
gerçek media ID'ye bağlıdır: resim decode edilmiş frame, video initialized/error-free,
audio başarılı source hazırlama sinyali olmadan ACK yoktur. Boş/broken içerik,
covered/background route, session değişimi ve farklı hedef ACK üretemez.

GET hatası açık retry ister; resume hata retry'ı başlatmaz. ACK hatasında içerik
korunur ve mevcut tek-uçuş exact ACK retry kullanılır; yeniden GET/navigasyon/bulk
read yoktur. İkinci farklı native medya girişi eski açık route tarafından tutulmaz.
COMMENT mevcut medya yorum alanına ulaşır; ayrı yorum ürünü eklenmez.

V6 capability sonrasında yalnız eski sürüm binary'sine dönmek uyumlu değildir;
containment, uyumlu binary üzerinde ilgili türlerin allowlist'ten çıkarılmasıdır.
