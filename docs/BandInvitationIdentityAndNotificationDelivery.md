# Grup daveti kimliği ve kalıcı bildirimler

Her yeni davet ve yeniden davet `BandMember.invitationId` için yeni bir UUID üretir.
Kabul/ret, üyelik okumasından önce alınan grup kilidi altında tam bu kimliği
karşılaştırır. Kimliksiz eski istemci veya önceki davetin kimliği mevcut bekleyen
daveti değiştiremez; `409 / 9220 BAND_INVITE_STALE` döner. Karar verilmiş davet
`400 / 9206 BAND_INVITE_STATUS_INVALID` döner.

- `GET /api/v1/user/bands/invitations/received` satırları mevcut alanlara ek
  olarak `invitationId` taşır.
- `GET /api/v1/user/bands/{bandId}/invitations/received/current` yalnızca
  oturumdaki müzisyenin bu gruptaki güncel PENDING davetini aynı DTO ile döndürür.
  İki okuma da `Cache-Control: no-store, private` kullanır.
- `POST /api/v1/user/bands/{bandId}/accept?invitationId=<uuid>` ve `/reject`
  açık ekrandaki davetin kimliğini ister. Eski bildirime tıklayan kullanıcı güncel
  davetlerine yönlendirilir; güncel kimlik karar isteğine sessizce eklenmez.
- `BAND_INVITE_RECEIVED` bildirim payload'ı `invitationId` içerir. Kabul/ret
  kimliği korur; yeniden davet değiştirir. Üyelikten ayrılma/geri katılmada
  kişisel etkinlik yayın izinlerinin sıfırlanması korunur.

Grup üyelik ve sanatçı/mekân bağlantı bildirimleri
`TransactionalNotificationService` ile alan işleminin içinde doğrudan kalıcı
bildirim tablosuna yazılır. Yazma başarısızsa alan işlemi de geri alınır.
WebSocket/Redis güncellemeleri commit sonrasında yapılır; bu geçici teslimat
kanalları başarısız olsa da bildirim sonraki REST okumasında mevcuttur. Bu yol
yalnız `emailForce=false` uygulama içi bildirimler içindir. Rabbit/outbox
altyapısını kullanan diğer modüllerin e-posta politikası değişmez. Tekrar
işleme karşı kaynak eventId, içerikten ayrı kalıcı teslim alındısıyla korunur.
Bu, aynı eventId ile farklı alan işlemlerini başarılı sayan bir idempotent komut
API'si değildir; alan işleminin kendi davet/üyelik sürüm kapısı da gereklidir.

WebSocket teslimatı da REST'in güncel aktör kimliği temizleyicisini kullanır;
gecikmiş mesaj/takip bildirimlerindeki önceki gerçek isim veya fotoğraf, güncel
GHOST görünürlüğünü aşamaz. Kimlik okunamazsa tanımlayıcı anlık görüntü silinir.

Dağıtım öncesi `scripts/db/2026-09-07-band-invitation-identity.sql` uygulanmalıdır.
Betik yalnız kimliği eksik PENDING kayıtları doldurur ve yeniden çalıştırılabilir;
eski bildirimleri, üyelik durumunu, başlığı veya yayın iznini değiştirmez.
Backend yazarları ve istemci sözleşmesi birlikte güncellenmelidir; eski backend
yazarları yeni davetlerde kimlik döndürmez. Yerel başlangıç kaydı betiği içerir.
Bu geliştirme sırasında betik canlı veritabanına uygulanmadı.

Grup oluşturma kotası yalnız kullanıcının `ACTIVE + FOUNDER` üyeliklerini sayar.
MEMBER, MANAGER ve diğer roller oluşturma hakkını tüketmez; davet kabulüne yeni
bir toplam üyelik sınırı eklenmez. Oluşturma işlemi hesap satırına write lock
aldıktan sonra kotayı okur ve kurucu kaydını aynı transaction içinde ekler;
aynı hesabın eşzamanlı oluşturma istekleri son hakkı birlikte kullanamaz.
Mevcut `my-bands` yanıtı tüm aktif üyelikleri korur ve her satırda
`countsTowardCreationLimit` alanıyla yalnız kurucu üyelikleri işaretler.
Bağlamsız grup ayrıntılarında bu alan null olabilir.

## Eski kadro ekranları

`BandMember.titleVersion`, yalnızca ünvan sürümü değil, aynı zamanda üyelik
dönemi sınırıdır: her yeniden davette ve her kabulde, eski ünvan null olsa da
artar. LEFT/REJECTED kayıt yeniden kullanılırken sıfırlanmaz. Taşma halinde
yeni döneme geçiş reddedilir; sürümün başa dönmesine izin verilmez.

- `DELETE /api/v1/user/bands/{bandId}/remove/{userId}?expectedTitleVersion=<long>`
  ve `PATCH /api/v1/user/bands/{bandId}/leave?expectedTitleVersion=<long>` açık
  ekrandaki özgün üye sürümünü ister. Legacy PUT `/leave` aynı kapıyı kullanır.
- Eksik, negatif veya güncel kayıtla eşleşmeyen sürüm `409 / 9221
  BAND_MEMBER_VERSION_CONFLICT` döndürür. İstemci kadroyu yeniler ve kullanıcı
  tekrar seçer; yeni sürümle sessiz tekrar yoktur.
- Ünvan güncelleme zaten `expectedTitleVersion` ister ve `409 / 9215` döndürür.
  Aradaki bir ünvan değişikliği de çıkarma/ayrılma ekranını eskimiş sayar.
- Mevcut ACTIVE satırın açıkça taşınan `0` sürümü geçerlidir. Ek dönem kimliği
  veya veri geçişi gerekmez; kurucu çıkarma/ayrılma yasağı değişmez.
- Aynı sürümle tekrar çıkarma, LEFT/REJECTED kayıt için yeni bildirim veya
  etkinlik yayın sürümü üretmez. Eski sürüm yeni PENDING/ACTIVE dönemi etkileyemez.

## Silinen bildirimin tekrar oluşmasını engelleme

`tbl_notification_receipt` yalnız `source_event_id`, `recipient_id` ve
`recorded_at` tutar; başlık, mesaj, payload, avatar veya görüntü kopyalanmaz.
Rabbit ve doğrudan transactional bildirim yazıcıları aynı transaction içinde
`INSERT ... ON CONFLICT DO NOTHING` ile kimliği alır. Tekillik denetimi ve
bildirim yazısı birlikte commit/rollback olur. Eşzamanlı tekrar teslim bu
ortak kapıda bekler; yalnız kazanan yeni içerik/yan etki üretir.

Silme, tümünü temizleme ve içerik saklama süresi temizliği, kimliği olan eski
kayıtların teknik alındılarını da silmeden önce korur. Alındılar içerik
temizliğiyle silinmez ve otomatik süre aşımına sahip değildir. Bu küçük teknik
kayıtların büyümesi kapasite planlamasında izlenmelidir.

Dağıtım öncesi yedek alınarak
`scripts/db/2026-09-07-notification-replay-receipts.sql` uygulanmalıdır; mevcut
kimlikli kayıtları içeriksiz biçimde doldurur, tekrar çalıştırılabilir ve yerel
başlangıç betiğinde kayıtlıdır. Bu denetimde yalnız kaynak dosya oluşturuldu;
canlı DB veya kuyrukta işlem yapılmadı. Eski ve yeni bildirim yazıcı/tüketicileri
karışık biçimde çalıştıran bir geçiş, yeni garantiyi sağlamaz; koordineli
geçiş gerekir.

`NotificationProducer.publish`, eksik eventId'yi yalnız yayın sınırında bir
UUID ile tamamlar ve var olan kimliği korur. Durable outbox/confirmed yayıncılar
zaten sabit kimlik taşır. Tüketici kimlik üretmez: eski kimliksiz kuyruk mesajı
`AmqpRejectAndDontRequeueException` ile mevcut dead-letter kuyruğunda inceleme
için tutulur; içerik loglanmaz ve kuyruk temizlenmez. Geçmişte kaydedilmiş
null-source satır silinebilir fakat eski mantıksal olayla eşleştirilemez; bu
geçmiş veri için geriye dönük kimlik/replay garantisi verilmez.

WebSocket/Redis teslimatı hâlâ commit sonrası best-effort izdüşümdür. Farklı
işlemlerin frame sırası veya daha önce yola çıkmış frame, DB silme işlemiyle
yarışabilir; REST inbox ve unread sayısı otoritedir. Teknik alındı yeniden DB
kaydı oluşmasını engeller, dağıtık frame sıralama protokolü değildir.

## Karşıörnek kapsamı

| Durum | Koruma / test kaynağı |
| --- | --- |
| Grup adı veya aktif mekân değişince HashSet bağlantısının silinememesi | BandEntityIdentityTest; aynı kimliğin değişmez hash'i |
| Ünvan/davet durumu değişince kadro üyeliğinin koleksiyonda kaybolması | BandEntityIdentityTest; BandMember UUID kimliği |
| Üye/yönetici davet hedefini yetkisiz sorgulama | BandServiceImplPublicationLifecycleTest; hedef hesabından önce kurucu kapısı |
| Ayrıl/çıkar → yeniden davet → kabul → eski çıkar/ayrıl/ünvan | BandServiceImplPublicationLifecycleTest; sürüm değişimi, yayın ve bildirim yan etkisi yok |
| Grup silinmişken profil güncelleme | BandProfileUpdateFenceTest; ortak grup write lock öncesi yazı yok |
| Sil/tümünü temizle/süre temizliği → geç Rabbit tekrar teslimi | NotificationReplayReceiptPostgresTest; içerik yeniden oluşmaz |
| Eşzamanlı aynı olay, silme commit/rollback ile tekrar teslim yarışı | NotificationReplayReceiptPostgresTest; gerçek PostgreSQL unique-index/transaction yarışı |
| Çok alıcılı bildirimde sonraki yazı başarısızlığı | NotificationReplayReceiptPostgresTest; önceki içerik ve alındı birlikte rollback |
| Teknik alındı göçü, tekrar çalıştırma, null-source geçmiş | NotificationReplayReceiptPostgresTest; yalnız üç metadata kolonu |
| Eski yayıncı eventId tamamlama ve sabit kimlikli tekrar | NotificationProducerTest |
