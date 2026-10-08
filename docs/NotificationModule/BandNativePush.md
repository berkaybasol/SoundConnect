# BAND native push sözleşmesi

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


`ANDROID_NATIVE_V7`, önceki V6 ailelerini korur ve yalnız `BAND_INVITE_RECEIVED`, `BAND_INVITE_ACCEPTED`, `BAND_INVITE_REJECTED`, `BAND_MEMBER_REMOVED`, `BAND_MEMBER_LEFT` türlerini ekler. V1–V6 ve null capability için BAND native fallback yoktur. `2026-09-29-push-native-band-capability.sql` eski capability değerlerini koruyan ileri migration'dır; eski migration dosyaları değiştirilmez.

`ANDROID_BAND_V1` data-only FCM verisi tam olarak `notificationId`, `recipientId`, `type`, `presentationVersion`, `displayVariant`, `sentAt`, `expiresAt` alanlarından oluşur. `displayVariant=DEFAULT`; canonical kimlikler ve zaman/TTL doğrulanır. Kullanıcı/grup adları, avatar, iş hedefi, invitation/actor kimliği ve untrusted başlık/metin wire'a eklenmez. Android beş tür için sabit Türkçe anonim metinleri mevcut native renderer ile gösterir.

Fiziksel Vivo kabulünde cihaz saati API'den yaklaşık 640 ms geride ölçüldü; hızlı teslimatlar sıfır toleranslı gelecek-zaman kontrolünde kart üretmedi. BAND Android/Dart parser'ları en fazla 5 saniye `sentAt` saat farkına izin verir. Süresi dolmuş mesajlar, `sentAt >= expiresAt` ve 28 günü aşan TTL reddedilir; tolerans expiry'yi uzatmaz. Android yalnız gösterim zamanını cihazın şimdiki zamanıyla sınırlar. Önceki ailelerin zaman sözleşmesi bu dar düzeltmede değiştirilmedi.

Planner ve gönderim öncesi revalidation, bildirim kimlik sürümünü ve kapalı payload'ı; iki gerçek aktif/verified/unerased müzisyeni, listener dışlamasını, grubu ve türün gerektirdiği üyelik/kurucu/davet durumunu doğrular. Preference, recipient, application scope, device generation/revision/revoke, retry ve event/device dedup kapıları mevcut zincirde korunur. V7 önceki DM/venue/application/studio/follow/media desteğini kaybetmez.

Native kart dokunuşu, geçerli recipient/epoch/reset kapısından sonra Dart'a yalnız notification/recipient/type taşır. Yetkili exact notification GET'i güncel version1 BAND payload'ını ve actor/band/invitation kimliklerini doğrular. Davet exact karar ekranına, kabul/ret/ayrılma güncel grup profiline, çıkarılma MyBands'e gider. Son yol çıkarılmış grubun güncel aktif üyelik listesinde bulunmadığını da doğrular.

Read, aynı oturum/route/foreground içinde gerçek hedef içeriği başarıyla görünür olduğunda yalnız ilgili notification için yapılır. Error, loading, session change, stale davet veya generic fallback read değildir. Stale davetin güncel davete açık yönlendirmesi eski read ticket'ını taşımaz; karar POST'u ekranda doğrulanan exact invitationId'yi kullanır.

## Kurulum ve containment

Sıra: ileri capability SQL → V7 anlayan API, eski allowlist korunarak → aynı imzalı APK `install -r` → normal UI login/enrollment'da V7/scope/generation/revision → kalıcı allowlist'e yalnız beş BAND türü eklenerek LOCAL24. Yeni capability kaydından sonra eski API/APK'ya kör geri dönüş yapılmaz; yeni türleri kapatmak ve uyumlu API/SQL/güncel tombstone/receipt durumunu korumak containment yoludur.
