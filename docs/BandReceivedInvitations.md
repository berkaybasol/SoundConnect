# Gelen grup davetleri — 7 Eylül 2026

Müzisyen → Yönetim Paneli → Bandlerim ekranı artık aktif grupları ve **Gelen
Davetler** listesini birlikte gösterir. Üstteki tekrar eden açıklamalar yerine
kompakt grup sayısı, boş durum kartı ve ortak gradient çerçeveli Grup oluştur
butonu kullanılır. Oluşturma sınırı üç aktif kurucu üyeliğidir; başka gruplardaki
MEMBER/MANAGER üyelikleri bu hakkı tüketmez. `my-bands` yanıtındaki
`countsTowardCreationLimit` alanı sayacın kaynağıdır.

## Kaynak ve güvenlik

`GET /api/v1/user/bands/invitations/received?page=0&size=20`

- Kaynak bildirim geçmişi değil, oturumdaki kullanıcının PENDING BandMember
  kayıtlarıdır. Bildirim silinse veya davet bu özellikten önce gönderilmiş olsa
  da okunur. İstemciden userId/requesterId alınmaz.
- HTTP ve servis katmanlarında aktif, doğrulanmış müzisyen hesabı gerekir.
  Liste kurucu olmayı gerektirmez. Yetki, sayfa ve medya aynı read-only
  REPEATABLE_READ snapshot içinde okunur. Yanıt `Cache-Control: no-store, private`.
- Yanıt standart PageResponse alanlarını ve yalnız
  `{bandId, bandName, profilePicture, status: "PENDING", invitationId}` satırlarını içerir.
  Üye listesi, hesap bilgileri veya medya kimlikleri yüklenip dışarı verilmez.
- Sıra `coalesce(updatedAt, createdAt) DESC NULLS LAST, id DESC`. Sayfa 0–10000,
  boyut 1–50, toplam offset en fazla 100000. Grup fotoğrafları mevcut sayfanın
  tek, dedupe edilmiş medya sorgusuyla çözülür. Yalnız PUBLIC/READY medya,
  diğer durumda placeholder. Boş sayfada medya sorgusu yok.
- Yeni 9219 hatası yetkisiz hesap için 403, mevcut 9218 sayfa hatası için 400.

## İstemci ve işlem akışı

- İlk 20 kayıt, devamı Daha fazla göster ile yüklenir. Sliver satırları tembel
  oluşturulur. Timer, per-card profil çağrısı veya sürekli sorgulama eklenmedi.
- Aktif grup ve davet yüklemeleri bağımsızdır. Hata boş liste olarak gizlenmez.
  Sonraki sayfa hatası mevcut satırları korur, aynı sayfa tekrar denenir.
  Yetki hatası özel davet satırlarını temizler. Yeni sayfada toplam değişmesi
  veya önceki kimlikle çakışma ilk sayfadan bir kez yeniler.
- Oturum değişiminde özel liste temizlenir. Eski oturum/istek neslinin geç
  yanıtları uygulanmaz. Transport expectedSessionKey denetimi korunur.
- Davet kartı mevcut BandInviteDecisionScreen'i açar. Bildirim metni olmadan
  açıldığında grup adıyla standart başlık gösterilir, davet eden kişi uydurulmaz.
- Kabul/ret mevcut yolları `invitationId` query parametresiyle kullanır. Her
  yeniden davet yeni kimlik taşır; eski/kimliksiz bildirim güncel daveti değiştiremez
  ve 9220 hatasıyla güncel davet listesine yönlendirir. Davet ekranı başlangıç oturumunu
  sabitler, kimlik kaybında işlemleri kapatır. Kabul/ret taşıma katmanında aynı
  hesaba bağlıdır. Çift gönderim ve işlem sürerken geri çıkış engellenir.
- Alt sayfadan dönünce grup/davet listeleri yeniden okunur. Eski ekrandaki
  `result == true` döndüğünde grubu körlemesine silme davranışı kaldırıldı.
  Yeni grup oluşturma, kabul/ret, profil dönüşü, uygulamaya dönüş ve manuel
  yenileme listeleri günceller. Etkinlik gösterim/üyelik politikaları değişmez.

## Dağıtım

Backend ve Flutter kaynakları yeniden yüklenmeli. Davet kimliği için
`scripts/db/2026-09-07-band-invitation-identity.sql` önce uygulanmalı; betik eski
PENDING davetlere tek seferlik kimlik atar. Veritabanı sıfırlaması veya daveti
yeniden gönderme gerekmez. Ayrıntılar `BandInvitationIdentityAndNotificationDelivery.md`.
Kullanıcının açık
backend'ine ve davet/etkinlik verisine geliştirme sırasında yazılmadı.

Yeni `idx_band_member_user_status` okuma indeksi JPA metadata'sında tanımlı.
Yerel `ddl-auto: update` backend yeniden başlatılınca oluşturur. Production
`validate` için `scripts/db/2026-09-07-band-received-invitations.sql` ayrı bir
dağıtım adımıdır, transaction dışında çalıştırılmalıdır. Bu oturumda canlı
veritabanında indeks komutu çalıştırılmadı.

## Doğrulama

- Backend odaklı paket **119/119** geçti, atlanan test yok. Alıcıya göre gerçek
  H2 sorgusu/sayfalama, yetkisiz erişim, hesabın JWT'den değil güncel durumdan
  kontrolü, batch medya çözümlemesi, eski gönderilen davetler ve etkinlik üyelik
  yayın regresyonları dahil.
- Güncel kaynaklar `.local-verification/band-received-*` dizinlerinde izole
  derlendi. Bilinen Windows JAR sonlandırma erişim hatasından sonra yalnız
  yeni derlenmiş JAR ve sınıflar test edildi. Test görevi başarılı tamamlandı.
  Loglar `band-received-compile.log` ve `band-received-test-execution.log`.
- İlk odaklı Flutter paketi **100/100** geçti. Sonrasında geç işlem yanıtı,
  çift gönderim, işlem sürerken geri çıkış ve eski yenileme yarışı testleri
  eklendi. Son tam Flutter paketi **2.216/2.216** geçti. Son statik analiz
  sorun bulmadı. İki depoda `git diff --check` temiz.
- Navy/light/black için 320 dp normal ve %200 yazı önizlemeleri üretildi.
  Navy normal/%200, light normal ve black %200 görselleri incelendi. Uzun grup
  adı kart sınırında kısalır, büyük yazıda satırlar taşmadan kaydırılabilir.
- Flutter logları: `build/band-received-tests.log`,
  `build/band-received-full-tests.log`, `build/band-received-analyze.log`.
  Önizlemeler: `build/band-received-{navy,light,black}-{1.0,2.0}.png`.

Manuel durak: aedrum daveti henüz kabul etmedi. Yeni ekranda Şahbaz daveti
kontrol edilecek, ardından kabulden sonra üyeliğin geri gelmesi ama kişisel
B-T1 gösteriminin kendiliğinden geri gelmemesi testine dönülecek.
