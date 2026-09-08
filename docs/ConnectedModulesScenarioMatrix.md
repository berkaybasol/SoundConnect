# Bağlı modüller — ikinci çapraz denetim

Tarih: 7 Eylül 2026. Kapsam: bildirim, müzisyen profili, grup üyeliği ve daveti,
sanatçı–mekan bağlantısı. Etkinlik katılımı ve kişisel/grup gösterimi bu
modüllerin değiştirebildiği sınırlar açısından kapsanır. Bu belge, ilk
`ConnectedProfileModulesAudit.md` raporuna ek ikinci denetimdir.

Güncel veri geçişi: 7 Eylül 2026'da kullanıcı backend'i durdurduktan sonra üç
geçiş yedek ve geri yükleme provasıyla yerel DB'ye uygulandı. 84 mevcut iş
tablosunun içeriği korundu. Ayrıntılar `ConnectedModulesLocalMigration20260907.md`.

## Yöntem

Yalnız başarılı telefon adımları değil, aktör × mevcut durum × işlem × istek
yönü kombinasyonları ve gecikmiş/tekrarlanmış işlemler incelendi. Her olası
cihaz/zamanlama kombinasyonunun tüketildiği iddia edilmez. Aşağıdaki modelin
sonlu durumları otomatik testlerle, önemli veritabanı yarışları geçici gerçek
PostgreSQL ile sınanır. Sahte repository/widget testleri cihaz uçtan uca testi
olarak sayılmaz.

İlk karşı-örnek koşuları:

- `ArtistVenueConnectionIdentityTest`: eski kodda 3/3 başarısız.
- `BandEntityIdentityTest`: eski kodda 7/7 başarısız.
- `artist_venue_selection_race_test.dart`: ilk 6 senaryo eski kodda başarısız.
  Bu koşu ayrıca ListTile üzerindeki yanlış Material katmanını yakaladı.

Kanıtlar çalışma alanı `.local-verification/connected-identity-before.log`,
`.local-verification/connected-band-identity-before.log` ve
`SoundConnect-Frontend/build/connected-selection-before.log` dosyalarındadır.
Sonraki testlerin güncel sonuçları aşağıdaki doğrulama bölümüne kaydedilir.

## Durum ve etki matrisi

| Akış / koşul | Korunması gereken sonuç | Otomatik kanıt |
| --- | --- | --- |
| Müzisyen→mekan, grup→mekan, mekan→müzisyen, mekan→grup | Hedef UUID doğru türde kalır, kişisel ve grup bağlantıları karışmaz | `ArtistVenueConnectionTransitionMatrixTest`, `artist_venue_application_flow_test.dart` |
| PENDING / ACCEPTED / REJECTED × kabul / ret / iptal / bağlantı kaldırma × gönderen / alıcı / yabancı | Yalnız doğru taraf ve doğru ilk durum işlem yapabilir. Hatalı işlem kayıt/bağlantıyı değiştirmez | 144 birleşim + 4 eksik kayıt, `ArtistVenueConnectionTransitionMatrixTest` |
| Karşılıklı aynı anda başvuru, aynı anda kabul/ret | Tek geçerli istek/karar. Yanlış tarafın kararı veya ikinci aktif bağlantı oluşmaz | `ArtistVenueConnectionRequestConcurrencyPostgresTest` |
| Eski kaldırılmış başvuru üzerinden yeni bağlantıyı silme | Yeni bağlantı korunur | Aynı PostgreSQL sınıfı |
| Kabul sırasında üyelik/temsil yetkisi kaybı veya grubun silinmesi | Band kilidi altında güncel yetki esas alınır | Aynı PostgreSQL sınıfı |
| Hesabın pasif/doğrulanmamış olması, onay sırasında pasifleştirme | Yeni oluşturma/kabul iki taraf kullanılabilir olmadan tamamlanmaz. Eski bağlantılar otomatik silinmez | Aynı PostgreSQL sınıfı, servis testleri |
| Aynı görünen iki ayrı müzisyen, yeniden adlandırılan grup, ilişki ekleme/çıkarma | Nesne eşitliği değişebilir isim/ilişkiye değil kalıcı kimliğe bağlıdır. Set üyeliği kaybolmaz | Identity testleri + PostgreSQL aynı-ad/proxy testleri |
| Bağlantı kabul/kaldırma sonrası iki tarafın aynı işlem içindeki profili | Venue ve Band inverse koleksiyonları birlikte güncellenir | PostgreSQL ilişki testi |
| PENDING davet kabul/ret, ret/ayrılma/çıkarma ardından yeniden davet | Eski invitationId yeni davete karar veremez. Eski bildirim yalnız güncel daveti açıkça açtırır | `BandServiceImplPublicationLifecycleTest`, `band_invitation_identity_test.dart` |
| Eski üye listesi üzerinden çıkarma, unvan düzenleme veya ayrılma | Eski titleVersion yeni üyeliğe uygulanmaz. Kullanıcı listeyi yenileyip yeniden seçer | Band yaşam döngüsü/controller testleri, `band_members_workspace_test.dart`, `band_leave_flow_test.dart` |
| Kurucu / sıradan üye / yabancı / sona ermiş üyelik | Yönetim yetkisi sunucuda doğrulanır. Kurucu kendiliğinden ayrılmaz, başka kurucu çıkarılamaz | Band yaşam döngüsü ve controller testleri |
| Üyelik ayrılma/çıkarma/yeniden kabul | Yalnız ilgili kişinin etkinlik gösterim tercihleri sıfırlanır. Grup ve mekan etkinlikleri ile başkasının tercihleri korunur | `BandServiceImplPublicationLifecycleTest`, event performer testleri |
| Kurucu olunan 0/1/2/3 grup, başka gruplarda üyelik, eşzamanlı oluşturma | Yalnız aktif kurucu olunan gruplar kotaya girer. Üyelik kotayı tüketmez | `BandCreationQuotaTest`, MyBands testleri |
| Başarısız bildirim kaydı veya alan işlemi | Üyelik/bağlantı ve bildirimin kalıcı kaydı aynı işlemde geri alınır | `TransactionalNotificationServiceIT`, PostgreSQL rollback testleri |
| Aynı mesajın tekrarı, silme/temizleme ardından gecikmiş tekrar | Teknik teslimat kaydı korunur. Silinen metin/fotoğraf tekrar oluşturulmaz | Receipt PostgreSQL / listener / producer testleri |
| Tümünü temizleme ardından geç soket mesajı veya eski silme hatası | Bilinen silinmiş kimlikler gösterilmez. Bilinmeyen geç mesaj sunucudan doğrulanır, gerçek yeni bildirim korunur. Eski hata temizlenmiş listeyi geri getirmez | `notification_clear_projection_test.dart` |
| Gecikmiş kimlik bildirimi | Güncel gizlilik temizliği anlık gönderimde de uygulanır | Notification service/listener testleri |
| Ekran açıkken hesap değişimi, eski yanıt, çift dokunma | Eski hesaba ait veri/başarı mesajı yeni hesaba taşınmaz. Tek karar gönderilir | Frontend session, mutation, invitation ve application testleri |
| Üye seçimi: aynı görünen isimler, ayrılmış eski üye, farklı kimlik türleri | Kullanıcı UUID ile seçilir. İsim eşitliği başka hesabın davetini engellemez | `band_members_workspace_test.dart` |
| Arama yazısı değişirken eski yanıt / seçim, çift not penceresi | Eski sonuç anında geçersizleşir. Görünmeyen seçim gönderilmez, yalnız tek pencere açılır | `artist_venue_selection_race_test.dart` |
| Not penceresinde çift gönder/iptal, kapanmış veya başka rotayla örtülmüş pencere | Tek karar verilir, alttaki sayfa yanlışlıkla kapanmaz. 255 karakter sınırı ve hatalı taslak korunur | Aynı seçici testi, her iki yön |
| Şehir/ilçe değişimi ve sıfırlama sırasında geç yanıt | Eski şehrin ilçesi veya eski ilçenin semti yeni filtreyi ezmez | Aynı seçici testi |
| İlk/sonraki sayfada hata, başka cihazda karar, yetki kaybı | Geçici hatada tekrar denenir. Yetki kaybında özel liste temizlenir. Değişmiş karar yeniden yüklenir | `artist_venue_application_flow_test.dart` |
| Liste toplamı kayması, çakışan sayfa, yenileme sırasında eski yanıt | Yinelenen satır biriktirilmez, gerekirse sayfa sıfırdan yenilenir | Sayfalama widget ve repository testleri |
| Profilde bağlantı listesi başarıyla boş geldiğinde | Eski gömülü mekanlar geri getirilmez. Public profil özel istek API'sini çağırmaz | `musician_profile_calendar_composition_test.dart` |
| Müzisyen fotoğrafı/sosyal bağlantı/açıklama kaydı başarısız veya hesap değişmiş | Sahte başarı gösterilmez. Açıklama taslağı hata halinde korunur, eski hesap yazısı gönderilmez | Müzisyen güncelleme/cubit ve widget testleri |
| Profil kartı / etkinlik / üye listesi / bildirimden kendine veya sahip olunan gruba gitme | Yetkili ekran güncel kimlikle açılır, yabancı profile yönetim hakkı verilmez | Profile route resolver/gate, band member resolver ve event detail navigation testleri |

## Bu turda onaylanan ürün kararları

1. Eski üye ekranından çıkarma/unvan işlemi yeni üyeliği etkileyemez.
2. Aynı koruma kendi gruptan ayrılma işlemi için de geçerlidir.
3. Yeni mekan bağlantısı oluşturma ve kabulde iki taraf aktif/doğrulanmış
   olmalıdır. Grup için mevcut FOUNDER/MANAGER yetki modeli korunur ve en az
   bir kullanılabilir aktif temsilci aranır. Ret/iptal/kaldırma bu yeni
   kullanılabilirlik koşulundan bağımsız olarak mevcut yetkiyi gerektirir.
4. Silinen bildirim gecikmiş tekrar teslimatıyla geri gelmemelidir. Yalnız
   kaynak olay kimliği, alıcı kimliği ve teknik kayıt zamanı saklanır.
   Metin/fotoğraf/payload teknik kayıtta bulunmaz.

## Korunan kurallar ve kapsam sınırları

- Mekan bağlantısı ile etkinlik katılımı/gösterimi ayrı izinlerdir.
  Bağlantı kaldırmak eski etkinlik onayını otomatik geri almaz.
- Kişisel bağlantı ile grup bağlantısı birbirinden bağımsızdır.
- Aktif grup üyesi önceki bağlantı başvurularının geçmişini görebilir.
  Yeni üyeye eski bildirimler geriye dönük dağıtılmaz.
- Bağlantıda MANAGER desteği, etkinlikte kurucu temsili korundu.
- İptal/ret/kaldırma aynı REJECTED durumu ile saklanmaya ve iptal/kaldırma
  yeni bildirim üretmemeye devam eder.
- Geçmişte kimliksiz saklanmış bir bildirimden tekrar kimliği türetilemez.
  Yeni üretici kimlik atar. Tüketici kimliksiz teslimatı sessizce silmez veya
  tekrar döngüsüne sokmaz, inceleme için mevcut kalıcı hata kuyruğuna (DLQ)
  yönlendirir. Gerçek kuyruk üzerinde bu denetimde işlem yapılmadı.
- Yönetici panelinden kullanıcıyı kalıcı silmenin tüm uygulama ilişkilerini
  temizlemesi bu dört modülün kullanıcı akışları dışındadır. Burada otomatik
  cascade veya gerçek hesap silme uygulanmadı.

## Doğrulama ve veri geçişi

Backend son izole koşu tamamlandı: **902/902 test, 71 sınıf, 0 hata, 0 atlama**.
Kaynaklar ve tüm Java test kaynakları derlendi. Çalıştırılan kapsam bildirim,
grup/üyelik/davet, müzisyen servis/mapper/repository/takvim, dört yönlü mekan
başvurusu ve etkinlik katılımı/gösterimi sınırlarıdır. Bu sayı tüm backend
projesindeki her modülün testi olarak sunulmaz. Derlemedeki mevcut Lombok/
MapStruct ve deprecated API uyarıları sıfır uyarı iddiasına dahil değildir.

- Mekan başvurusu matrisi: **148/148** (144 birleşim + 4 eksik kayıt).
- Başvuru PostgreSQL işlem/yarış/kimlik testleri: **36/36**.
- Bildirim silme/tekrar/rollback/geçiş PostgreSQL testleri: **12/12**.
- Etkinlik PostgreSQL akış sınırları: **51/51**.
- H2 kullanılan iki transaction entegrasyon sınıfı ayrı sabit bellek
  datasource'una bağlıdır. PostgreSQL sınıfları geçici container kullanır.
  İki eski repository testi de gerçek uygulama datasource'una geri dönüş
  ihtimali kaldırılarak yalıtıldı. Docker yoksa bunlar gerçek DB'ye bağlanmaz.

Kanıt: `.local-verification/connected-second-backend-final.log` ve
`.local-verification/connected-audit-build/test-results/test/TEST-*.xml`.
Tekrar koşu: `.local-verification/verify-connected-modules.ps1`.

Son kaynak üzerinde tam Flutter koşusu **2372/2372** geçti. Son not penceresi
çift-karar koruması ve tüm regresyonlar dahildir. `dart analyze lib test`:
**No issues found**. İki repository için `git diff --check` temiz.
İlk denetimin 2306/402 sonucu yeni kaynakların son doğrulaması değildir.

Frontend kanıtları:

- `SoundConnect-Frontend/build/connected-second-flutter-final.log`
- `SoundConnect-Frontend/build/connected-second-analyze-final.log`

Bu denetimde saptanan kapsam içi somut hatalar düzeltildi ve ilgili
regresyonlarla doğrulandı. Modeldeki durum-geçiş kombinasyonlarının geçmesi,
her olası cihaz/ağ zamanlamasında sıfır hata garantisi olarak yorumlanmamalı.

Kod denetimi sırasında gerçek veritabanına yazılmadı, backend
durdurulmadı/başlatılmadı. Sonraki kullanıcı teyidiyle aşağıdaki üç geçiş
yedekli olarak uygulandı. B-T1 ve aedrum test verileri korunuyor. Backend'i
yeniden başlatma kullanıcıya bırakıldı. Geçiş raporu yukarıda bağlıdır.

Birlikte dağıtılacak geçişler:

1. `scripts/db/2026-09-07-band-invitation-identity.sql`
2. `scripts/db/2026-09-07-artist-venue-request-pages.sql`
   (CONCURRENTLY indeksleri, transaction dışında)
3. `scripts/db/2026-09-07-notification-replay-receipts.sql`

Backend ve Flutter güncel sözleşmeyle birlikte kullanılmalı. Üye çıkarma ve
ayrılma, görülen kaydın `expectedTitleVersion` değerini gerektirir. Geçiş
sırasında eski bildirim üreticileri/tüketicileri durmalı, backfill sonrasında
teknik kayıt kullanmayan eski bir writer çalışmaya devam etmemeli. Teknik
kayıtların otomatik silme süresi yoktur.

Gerçek cihazda yerel fotoğraf seçme/kırpma/yükleme ve gerçek push taşıması bu
otomatik koşularla uçtan uca doğrulanmış sayılmaz. B-T1 manuel test sırası
`EventManualTestProgress.md` içinde korunur.
