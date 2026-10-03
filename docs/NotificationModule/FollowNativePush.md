# Takip native push sözleşmesi

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


Kapsam yalnız `SOCIAL_NEW_FOLLOWER` ve `SOCIAL_NEW_BAND_FOLLOWER`.
Mevcut kalıcı follow outbox kaynakları, alıcı snapshot'ı ve receipt/inbox idempotency
korunur. Yeni bir olay üreticisi eklenmez.

## Cihaz ve migration sırası

`2026-09-28-push-native-follow-capability.sql` önceki beş capability marker'ını ve
doğrulanmış V4 constraint'lerini ister. V1–V4 satırlarını değiştirmeden V5'i ekler;
transaction içinde doğrulanmış CHECK'ler ve altıncı marker birlikte yazılır.
Tekrar uygulama cihaz satırlarını/marker tarihlerini değiştirmez. Dated eski SQL'ler
değiştirilmez. Normal launcher registry yeni migration'ı bilir; kurulu image pini
seçimi bundan bağımsızdır. Migration uygulamak tür allowlist'ini açmaz.

| Android kayıt capability | DM | Mekân/etkinlik 6 | Başvuru 2 | Stüdyo 6 | Takip 2 |
| --- | --- | --- | --- | --- | --- |
| ANDROID_DM_V1 | Evet | Hayır | Hayır | Hayır | Hayır |
| ANDROID_NATIVE_V2 | Evet | Evet | Hayır | Hayır | Hayır |
| ANDROID_NATIVE_V3 | Evet | Evet | Evet | Hayır | Hayır |
| ANDROID_NATIVE_V4 | Evet | Evet | Evet | Evet | Hayır |
| ANDROID_NATIVE_V5 | Evet | Evet | Evet | Evet | Evet |

Generic/iOS cihazına follow işi planlanmaz. Scoped başvuru kaydı V3/V4/V5'te yalnız
kendi başvurusunun iki türünü alır; follow scope dışıdır. Gerçek dağıtım ayrıca
yetkilendirilirse sıralama: ileri SQL → uyumlu backend ve pinli image güncellemesi →
yeni APK ve başarılı V5 cihaz kaydı → ayrıca yetkili allowlist/kabul adımı.

## Kapalı wire

Örnek kimlikler kurgusaldır. HTTP v1 mesajı yalnız `data` taşır; Android
notification bloğu, APNs veya webpush fallback'i yoktur.

```json
{
  "presentationVersion": "ANDROID_FOLLOW_V1",
  "notificationId": "50000000-0000-0000-0000-000000000001",
  "recipientId": "10000000-0000-0000-0000-000000000001",
  "type": "SOCIAL_NEW_FOLLOWER",
  "displayVariant": "DEFAULT",
  "sentAt": "1790000000000",
  "expiresAt": "1790000060000"
}
```

Yalnız yedi alan kabul edilir. Band türünde yalnız `type` değişir. Actor/band/profil
UUID'si, isim, avatar, body, route veya deeplink gönderilmez. Fixed kart metinleri
“Yeni bir takipçin var.” / “Grubunun yeni bir takipçisi var.”; kilit ekranı public
version'ı genel bildirimi gösterir. Native ortak renderer, kanal, ikonlar ve grup
lifecycle'ı kullanılır. Mevcut 17 görsel kaynak değiştirilmez.

Planner opt-in flag/allowlist, permission, active device, scope, preference/category,
TTL ve read kapılarını uygular. Claim sonrası prepare bunları yeniden ölçer; generation,
revoke, hesap silme, bildirim silme, actor/band bulunabilirliği tekrar doğrulanır.
Sonradan unfollow veya band üyeliğinin değişmesi geçmiş olayı kendiliğinden iptal etmez.
FCM kabulü fiziksel teslim veya okundu kanıtı değildir.

## Hedef ve exact read

Native kart ve inbox aynı follow opener'a girer. Current authenticated GENERAL
oturumu, süresi dolmamış captured token ve exact notification GET doğrulanır.
Recipient, notificationId, type/action ve hedef UUID'leri uyuşmalıdır. Kişi hedefi
canonical `profiles/by-user` üzerinden taze çözülür; DM'nin pozitif/negatif cache'i
ve in-flight sonucu follow için kullanılmaz. API hatası ile boş destekli hedef farklı
sonuçtur. Çoklu seçim ACK yapmaz; seçimden sonra hedef tekrar taze doğrulanır.

| Hedef | Davranış |
| --- | --- |
| MUSICIAN / VENUE / LISTENER / STUDIO | Mevcut public router ve gerçek hedef GET'i |
| Hayalet dinleyici | Güncel sınırlı hayalet görünümü; kimlik/action fallback'i yok |
| Stüdyo listener kısıtı | Bilgi ekranı profil başarısı sayılmaz; unread kalır |
| PRODUCER / ORGANIZER / boş / silinmiş / yasak | Yeni route yok; kullanılamıyor/retry, unread |
| Band | Exact notification GET bandId ile mevcut auto görünüm; güncel GET/üyelik, gerekirse public görünüm |

Dar follow read ticket'ı gerçek hedef isteğinin döndürdüğü nesneye, profile/band ID'ye,
kişide ayrıca owner user ID'ye bağlıdır. Loading, eski state, başka GET, yanlış hedef,
covered/disposed route, background tamamlanma ve değişmiş oturum/token ACK üretmez.
Yalnız başarıyla yüklenmiş doğru görünür içerikten sonra ilgili notificationId okunur.
GET hatasının retry'ı gerçek isteğe ulaşır. ACK hatası içeriği kapatmaz; mevcut ACK-only
retry çift dokunuşu birleştirir, hedefi tekrar açmaz. Kardeş unread/sayaç/native kartlar
yalnız doğrulanmış exact read uzlaştırmasıyla korunur. Native follow tap'ı ayrıca
recipient/epoch/reset bağını warm ve gecikmiş cold handoff'ta kontrol eder.
