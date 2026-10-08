# Mekân Backstage akışı — 14 Eylül 2026

Kullanıcı kararı: müzisyen akışının mevcut görünümü ve ortak davranışları korunur;
mekânda profil tamamlama bulunmaz, keşif/fırsat dengesi sanatçı bulmaya yönelir.
İkinci bir sayfalama, etkileşim veya sıralama motoru oluşturulmadı.

## API ve erişim

`GET /api/v1/feed/venue`, müzisyen endpoint'iyle aynı limit/cursor/
`supportedItemTypes` sözleşmesini kullanır. Alt uçlar da ortaktır:
`GET /muted-authors`, `POST /items/{itemId}/feedback`,
`PUT|DELETE /authors/{profileType}/{profileId}/mute`, `POST /events`.
Müzisyen uçları müzisyen rolüne kapalı sınırını korur; mekân uçları `ROLE_VENUE`
ve veritabanındaki tek kişisel profil rolünü doğrular. Yönetici rolleri kişisel
profil sayılmaz. Mekânın sahibi aktif, e-postası doğrulanmış ve silinmemiş olmalı;
mekân onaylı, kamuya görünür, konum zinciri geçerli ve profil kaydı mevcut olmalı.

Bir hesapta birden fazla uygun mekân varsa UUID sırasındaki ilk mekân kararlı
konum kaynağıdır. Hedef kimliği `Venue.id`'dir, `VenueProfile.id` değildir.
Feed, geri bildirim ve görülme geçmişi mevcut hesap düzeyinde kalır.

## İçerik politikası

- Takip edilen yayınlar, sosyal hareketler, ortak kartlar, beğeni/yorum, gerçek
  detay yönlendirmeleri, duyurular ve sponsor aralıkları aynı altyapıyı kullanır.
- Mekân keşif profilleri yalnız müzisyen ve gruptur. Parça ve performans
  video/ses içerikleri sanatçı keşfini destekler; takip edilen başka profil
  türlerinin yayınları korunur.
- Uygun arz varsa kalan normal seçim slotlarının yaklaşık %20'si
  (`floor(slots/5)`, en az beş slotta) müzisyen/grup profili, parça, performans
  video/sesi veya sanatçı etkinliğine ayrılır. Müzisyen tarafındaki uygun
  Collab/etkinlik rezervasyonu değişmez. Takip edilen sanatçılar da bu payı
  doldurabilir; %20 yeni veya yerel sanatçı garantisi değildir. Etkinliklerin
  takip/ilgili fırsat havuzunda olması gerekir. Hiçbir Collab ilanı sanatçı
  rezervasyonunu doldurmaz; normal uygun kartlar olarak kalabilir.
- Mekân şehri, müzisyenin mevcut `opportunity_city_id` tercihiyle karşılaştırılır;
  bu tercih boşsa hesap şehri kullanılır. Grup için aktif, doğrulanmış ve
  silinmemiş en az bir aktif üyenin eşleşmesi yeterlidir. Bu yalnız sıralama
  profil/parça/profil medyası aday seçimi ve sıralama sinyalidir; özel tercih/üye
  şehirleri public payload'a eklenmez. Etkinlikler mevcut mekân şehri eşleşmesini
  kullanır.
- Parça ve profil medyası sağlayıcılarında mekân aday bütçesinin yarısı takip havuzuna ayrılır;
  kalan bütçe önce yerel, sonra diğer sanatçı adaylarıyla dolar. Kullanılmayan
  takip bütçesi keşfe aktarılır. Bunlar nihai ekran yüzdeleri değildir; ortak
  mixer, çeşitlilik ve ayrılmış fırsat slotlarıyla son sayfayı seçer.
- Yerel sanatçı azsa ulusal havuz devam eder. Sağlayıcıların döndürdüğü aday
  sayısı sınırlıdır; SQL'in inceleyeceği satır sayısı veri dağılımı ve plana
  bağlıdır. Havuz dışındaki her içeriğin bulunması/gösterilmesi garantisi yoktur.
- `PROFILE_COMPLETION` backend yetenek kesişiminden çıkarılır; sağlayıcı/mixer
  ve Flutter'da da savunma vardır. Mekânın müzisyen tercih kaydı oluşturulmaz.

## Oturum ve operasyon

Mekân algoritma sürümü `venue-v1.0.0`, müzisyen `musician-v1.2.0`.
İmzalı cursor audience, algoritma ve mekân kimliğine bağlıdır. Başka rolün veya
değişen ana mekânın cursor'ı, kayıtlı sayfa replay edilmeden reddedilir. Aynı
mekândaki şehir/tercih değişimi yeni sayfa üretiminde ranking bağlamını geçersiz
kılar; daha önce yazılmış aynı isteğin cevabı mevcut replay semantiğiyle korunur.
Eski müzisyen cursor biçimi uyumludur. Flutter kimliği kullanıcı/token/rol
birlikte taşır; değişimde Cubit ve kaydırma durumu ayrılır, geç yanıtlar düşer.

Mevcut rate limit, imzalı teslimat kanıtı, moderasyon, gizlilik, beş kartlık
çeşitlilik geçmişi, 24 saatlik yumuşak görülme azaltması ve duyuruların altı saat /
24 saatte iki kabul kuralı aynıdır. Duyurular `VENUE` audience ile seçilir.
Paylaşılan ayar ve metriklerin tarihsel adı `app.feed.musician` ve
`soundconnect.musician.feed.*` kalır; bunlar artık ortak motoru kapsar.

Mekân akışı yeni migration gerektirmez. Önceki
`scripts/db/2026-09-14-musician-feed-recent-views.sql` performans indeksi hâlâ
dağıtımda uygulanmalıdır; gerçek veritabanına bu oturumda uygulanmadı.
Doğrulama ve yük testi kanıtları workspace
`.local-verification/venue-feed-20260914/` dizininde tutulur. Yük testi yalnız
izole PostgreSQL service/JDBC senaryosudur; HTTP/medya/üretim kapasitesi kanıtı
olarak yorumlanmaz.

## Bu sürümün doğrulaması

14 Eylül 2026: frontend 521 regresyon testi, backend 95 pakette 641 test geçti;
backend hata/atlama yok. Flutter `lib test` statik analizi temiz. İzole yük testi
her iki audience için de geçti. 50.000 parça / 10.000 etkinlik fixture'ında p95:

| Audience | Seri | 8 kullanıcı | 16 kullanıcı | Aynı cursor, 16 istek |
| --- | ---: | ---: | ---: | ---: |
| VENUE | 177,71 ms | 470,76 ms | 941,65 ms | 1.135,68 ms |
| MUSICIAN | 153,59 ms | 484,55 ms | 719,70 ms | 1.098,62 ms |

Her rol için 573/573 sağlayıcı çağrısı tamamlandı, 16/16 replay cevabı byte
düzeyinde aynıydı; hizmet uyarısı/hatası olmadı. Mekân yük testi gerçek
şehir/sahiplik kişiselleştirmesini de kapsar. Bu sayılar aynı geliştirme
makinesindeki sınırlandırılmış service/JDBC ölçümüdür, üretim SLA'sı değildir.
Kanıtlar `venue-load-report/`, `musician-load-report/` ve ilgili günlük/XML
arşivlerinde aynı doğrulama dizinindedir.
