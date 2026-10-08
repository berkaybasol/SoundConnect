# Planlı etkinlikler

Plan, mekanın özel yönetim kaydıdır. Her hazırlanmış tarih normal bir `Event` ve ayrı UUID alır. Public etkinlik kartı, detay DTO'su, katılım, yorum ve paylaşım hedefleri aynı kalır. Bir planın kimliği public `EVENT` veya `EVENT_POST` kimliği olarak kullanılamaz.

## Kullanım

- Mekan etkinlik formunda haftanın günlerini ve bitiş tarihini seçer; bitiş yerine **Ben durdurana kadar** kullanılabilir. Tekrar sayısı yoktur.
- Sunucu önizlemesi başlangıçtan veya bugünden itibaren ilk 28 takvim gününü gösterir. İstenmeyen tarihler çıkarılabilir.
- Etkinlik yönetimi **Etkinlikler** ve **Planlar** sekmelerine ayrılır. Etkinlikler sekmesinde bu hafta, gelecek ve geçmiş bölümleri birbirinden bağımsız açılıp kapanır. İlk açılışta bu hafta bölümü açıktır; boşsa gelecek bölümü açılır. Planlar sekmesinde aktif planlar önce gösterilir, **Geçmiş planlar** başlangıçta kapalıdır.
- Tek tarih atlanabilir veya ayrı düzenlenebilir. Ayrı düzenlenen etkinlik aynı UUID'yi korur ve sonraki program düzenlemelerinden ayrılır.
- Gelecek programı düzenleme, henüz başlamamış bağlı tarihleri günceller. Aynı tarihte kalan etkinlik kimliği korunur. Programdan çıkarılan gelecekteki etkinlik silinir; geçmişe dokunulmaz.
- Durdurma yeni tarih üretimini kapatır. Ayrıca hazırlanmış gelecek etkinlikleri iptal etme seçeneği vardır. Tamamlanan tarih aralığı uzatılabilir; durdurulmuş plan düzenlenerek kendiliğinden başlamaz.
- Etkinliği kopyalama, mekan sahibine özel `copy-source` endpoint'inden ham afiş ve sanatçı seçimlerini alır. Önceki katılım ve profil yayın kararı kopyalanmaz.

Plan düzenleme önizlemesi tarih defterini de okur: atlanan, iptal edilen, ayrı düzenlenen ve başlamış tarihler normal düzenleme tarihleri arasında gösterilmez. `preservedDates` bu kayıtların planlanan tarihini, güncel etkinlik tarihini ve korunma nedenini taşır. Taşınmış bir etkinliğin planlanan veya güncel tarihi 28 günlük pencereye giriyorsa kayıt önizlemeye dahil edilir. Önizleme sürüm ve sahiplik kontrolü yapar; hiçbir planı veya etkinliği değiştirmez.

## Onay ve yayın

Mevcut mekan bağlantısı bir programa katılım onayı sayılmaz. Seçilen sanatçı veya grubun güncel yetkili kurucusu programı açıkça kabul eder. Kabul sırasında profil takviminde yayın için gerçek JSON boolean zorunludur. Grup üyesinin kişisel yayın tercihi ayrı kalır.

Başlangıç/bitiş tarihi, günler, istisnalar, saat veya sanatçı değişirse program yeniden onay ister. Başlık, açıklama ve afiş değişikliği tek başına onayı sıfırlamaz. Sanatçı gelecek bağlı katılımını geri çekebilir; mekan etkinliği düz isimle korunur, bağlantı ve profil yayını kaldırılır. Bağımsız düzenlenen tarihler kendi tekil onay akışına sahiptir. Bekleyen bir programdan ayrılan tarih için de tekil davet hazırlanır.

Bildirimler mevcut transactional outbox üzerinden `module=EVENT_PLAN` ve `planId` payload'ıyla gönderilir. Tarih başına tekrar bildirim üretilmez. Eski bildirim açıldığında güncel sürüm, hedef ve yetki sunucudan okunur.

## İşlem sınırları

- Takvim bölgesi `Europe/Istanbul`. Üretim bugün ile bugün+27 arasında ve yalnız henüz başlamamış saatler içindir. Uzak başlangıçlı plan zamanı gelene kadar saklanır; geçmiş tarihler sonradan doldurulmaz.
- `event_plan_occurrences` anahtarı `(plan_id, scheduled_date)` kalıcıdır. Silme, atlama ve iptal kayıtları yeniden üretimi engeller. `event_id` silinince FK `SET NULL` uygular.
- Mutasyonlar ve üretim plan kaydını kilitler. Grup kilitleri sıralı alınır; kilit öncesi okunan yetki kilit sonrası tekrar doğrulanır. İstemcinin `expectedVersion` değeri eskiyse işlem çatışmayla durur.
- Oluşturma anahtarı `(organizer_user_id, client_request_id)` idempotenttir. Aynı anahtar farklı tanım için kullanılamaz.
- Scheduler her planda ayrı transaction kullanır. Anahtar üzerinden ilerleyen tarama, hata veren bir programın diğerlerini engellemesini önler. Birden çok uygulama süreci aynı tarihi çoğaltamaz.
- Sayfalar en fazla 50 kayıttır. Bir hesapta en fazla 50 aktif plan bulunur. Kota oluşturma ve yeniden uzatma sırasında güncel takvim kuralıyla hesaplanır; son tarihi başlamış bir planın kotadan çıkması scheduler'ın `COMPLETED` yazmasını beklemez. Redis limiti hesap başına dakikada 30 mutasyon ve ayrı 60 önizlemedir. Redis erişilemezse kullanıcı mutasyonu 503 ve `Retry-After` döner; zamanlayıcı Redis'e bağımlı değildir.
- Mekan sahibinin plan listesinde, takvim kuralına göre hâlâ aktif olan planlara veritabanı sayfalamasından önce öncelik verilir; ardından `updatedAt DESC NULLS LAST, id DESC` sıralaması uygulanır. Kota ve liste aynı etkinlik kuralını kullanır. Adaylar UUID sırasıyla 50 kayıtlık bloklarda taranır; scheduler'ın tamamladığı kayıtlar sonraki adayların atlanmasına yol açmaz. Son tarihi bitmiş fakat scheduler henüz `COMPLETED` yazmamış planlar da geçmiş grubuna sıralanır.
- Başlangıç ve bitiş saatleri tam saniye hassasiyetindedir (`HH:mm:ss`). Kesirli saniyeler, veritabanında sessizce yuvarlanıp önizleme ve kayıtlı programın farklılaşmasına izin verilmeden reddedilir.
- Henüz gerçek etkinliği oluşmamış plan afişi de medya silme korumasına dahildir.

## API

Tüm yanıtlar mevcut `BaseResponse.data` zarfındadır. Plan namespace'leri hata yanıtlarında da `Cache-Control: no-store, private` döner.

| Yetki | Yol | İşlem |
| --- | --- | --- |
| Mekan sahibi | `POST /api/v1/venue-owner/event-plans/preview` | Tanımdan tarih önizlemesi |
| Mekan sahibi | `POST /api/v1/venue-owner/event-plans/{id}/preview` | `expectedVersion`, `definition` ile mevcut planın korunan tarihlerini içeren önizleme |
| Mekan sahibi | `POST /api/v1/venue-owner/event-plans` | `clientRequestId` ve `definition` ile oluştur |
| Mekan sahibi | `GET /api/v1/venue-owner/event-plans/venue/{venueId}` | Sayfalı planlar |
| Mekan sahibi | `GET, PUT /api/v1/venue-owner/event-plans/{id}` | Oku / gelecek programı düzenle |
| Mekan sahibi | `POST /api/v1/venue-owner/event-plans/{id}/stop` | `expectedVersion`, `cancelFuture` |
| Mekan sahibi | `GET /api/v1/venue-owner/event-plans/{id}/occurrences` | Sayfalı tarih defteri |
| Mekan sahibi | `PUT /api/v1/venue-owner/event-plans/{id}/occurrences/{date}` | Bir tarihi ayrı düzenle |
| Mekan sahibi | `POST /api/v1/venue-owner/event-plans/{id}/occurrences/{date}/skip` | Tarihi atla |
| Mekan sahibi | `GET /api/v1/venue-owner/events/{eventId}/copy-source` | Yeni taslak için ham alanlar |
| Sanatçı / güncel grup kurucusu | `GET /api/v1/user/event-plans?targetType=MUSICIAN\|BAND&targetId=...` | Yetkili hedefin planları |
| Sanatçı / güncel grup kurucusu | `GET /api/v1/user/event-plans/{id}` | Güncel program ve karar izinleri |
| Sanatçı / güncel grup kurucusu | `POST /api/v1/user/event-plans/{id}/decision` | `ACCEPT`, `REJECT`, `WITHDRAW` |

## Yayına alma

1. Mevcut veritabanı yedeğini ve önceki migration'ların uygulanmış olduğunu doğrula. Uygulama yazıcılarını durdur.
2. `scripts/db/2026-09-21-event-plans.sql` dosyasını hedef PostgreSQL üzerinde uygula. Migration transaction, kilit/zaman aşımı ve tekrar uygulanabilir constraint kurulumları içerir. Mevcut etkinlikleri dönüştürmez. Hibernate'in tek başına oluşturduğu tablolar kalıcı FK davranışı için yeterli değildir.
3. Backend'i, ardından bu API'leri kullanan Flutter sürümünü yayınla. Yerel `scripts/dev.ps1` migration listesine bu dosya eklenmiştir.
4. Bir test mekanıyla plan, onay, tek tarih atlama ve iki durdurma biçimini doğrula. Scheduler varsayılan ilk bekleme ve çalışma aralığı 60 saniyedir (`app.event-plans.initial-delay-ms`, `app.event-plans.poll-delay-ms`).

Kod geri alınacaksa yeni üretimi önce durdur; ek tabloları ve tarih defterini koru. Hazırlanmış etkinlikler normal kayıtlar olarak kalır. Defteri silmek veya yeniden yaratmak, geri yüklemeden sonra tarihlerin yeniden oluşmasına neden olabilir.

## İzole doğrulama

Plan PostgreSQL testleri geçici Testcontainers veritabanını açıkça bağlar ve JDBC adresini doğrular. Migration testleri yeniden uygulamayı, Hibernate ile oluşturulmuş şema uyumunu ve silme kayıtlarını kontrol eder. Redis testleri ayrı geçici container üzerinde eşzamanlı kota tüketimini doğrular. HTTP testleri gerçek güvenlik zinciriyle rol, boolean ve cache sınırlarını kontrol eder.

Testlerde `application-test.yml` açıkça seçilmeli ve `spring.config.import` boş bırakılmalıdır; geliştirme `.env` dosyasının içe aktarılmasına güvenilmemelidir. Uygulama sunucusunu veya yerel compose ortamını başlatmak bu testlerin parçası değildir.

Java 21 ve çalışan Docker ile backend kökünden [`scripts/test-event-plans.ps1`](../scripts/test-event-plans.ps1) çalıştırılabilir. Script varsayılan olarak yerel Gradle önbelleğini kullanır. Eksik bağımlılıkların indirilmesi gerektiğinde `-AllowDependencyDownloads` eklenir.

```powershell
.\scripts\test-event-plans.ps1
.\scripts\test-event-plans.ps1 -IncludeConsumerRegression
```

İkinci komut mevcut etkinlik, takvim, katılım, gönderi, yorum ve medya referans testlerini de kapsar. [`event-plan-verification.gradle`](../scripts/gradle/event-plan-verification.gradle) yalnız test konfigürasyonunu seçer; derleme çıktısı, proje önbelleği ve `test.log` dosyası `build/event-plan-verification` altında tutulur. Testler aynı Gradle çalışma dizininde eşzamanlı başlatılmamalıdır.

### 21 Eylül 2026 doğrulama kaydı

- Geniş backend turu: 68 sınıfta 614 test çalıştırıldı. Son düzeltmenin derlemeye yetişmemesiyle oluşan bir sonuç ve eski Redis test diliminin konfigürasyonundan gelen üç sonuç, takip turunda tekrar doğrulandı.
- İlk uygulamanın odaklı takip turu: **75/75 geçti**, hata ve atlanan test yok. Gerçek PostgreSQL plan senaryoları **15/15**, migration **5/5**, plan Redis **2/2**, mevcut takvim Redis **3/3**. Grup kurucusunun değişmesi ve kişisel yayın sürümünün sıfırlanması da kapsandı.
- Mevcut planı düzenleme önizlemesi düzeltildikten sonraki odaklı backend turu: **80/80 geçti**, hata ve atlanan test yok. HTTP sözleşmesi **24/24**, service testleri **9/9**, gerçek PostgreSQL plan senaryoları **16/16**. Atlanan, silinen, taşınan ve başlamış tarihlerin önizlemede ayrılması; tarih penceresi, sahiplik/sürüm denetimleri ve önizlemenin kalıcı veriyi değiştirmemesi doğrulandı. Bu turlar ortak testleri tekrar çalıştırır; test sayıları birbirine eklenmez.
- Aktif planların sayfalama öncesinde öne alınmasından sonraki odaklı backend turu: **81/81 geçti**, hata ve atlanan test yok; gerçek PostgreSQL plan senaryoları **17/17**. Dört sayfa boyunca aktif/geçmiş sırası, eşit güncelleme zamanında UUID sırası, scheduler öncesi tamamlanma, aktif plan kalmaması ve mekan izolasyonu doğrulandı. Önceki turlarla örtüşen sayılar toplanmaz.
- Son Flutter turu: plan, takvim, davet, navigasyon ve menü regresyonlarında **10 test dosyasında 110/110 geçti**. Değişen dosyaların hedefli analizi temiz. Açık/koyu tema, dar ekran ve büyük yazı kontrolleri ile fiziksel Android cihaz doğrulaması yapıldı. Önceki **100/100** ve **27/27** turları bu kapsamla örtüşür; sayılar toplanmaz.
- Public etkinlik kartı/detay görünümü değiştirilmedi. 21 Eylül 2026'da yerel veritabanının yedeği alındıktan sonra plan migration'ı uygulandı. Yerel IntelliJ backend + Android cihaz manuel kabulü tamamlandı: oluşturma, kopyalama, tek tarih düzenleme/atlama, genel güncelleme, iki durdurma yolu, sanatçı onayı/reddi/geri çekmesi, ayrı profil yayını ve eski onay engeli doğrulandı.
- **19:17 yerel temizlik kontrolünde** 30 kullanıcı ve 2 orijinal etkinlik korundu; QA etkinliği kalmadı. İki test planı durduruldu ve defter kayıtları korundu. Zamanlayıcı sonrası yeniden üretim yoktu. Bu sayıların ardından kullanıcı aktif bir `deneme` planı ve yeni bir tekil etkinlik ekledi. Son sekme/görünüm turunda canlı plan veya etkinlik kaydı değiştirilmedi; bu kullanıcı verileri korundu. Doğrulamalar yerel IntelliJ backend ve Android cihazı kapsar; production dağıtımı yapılmadı.
