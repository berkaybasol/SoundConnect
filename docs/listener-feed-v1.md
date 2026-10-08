# Dinleyici akışı v1

Dinleyici akışı, müzisyen ve mekân akışıyla aynı kart, etkileşim, sayfalama,
teslimat kanıtı, geri bildirim ve moderasyon altyapısını kullanır. Algoritma sürümü
`listener-v1.0.0`, yanıt şeması sürümü `1` olarak ayrılır.

## Kapsam ve içerik

- Takip edilen kişilerin uygun sosyal paylaşımları, parçalar, performans medyası
  ve etkinlikler ortak kartlarla gösterilir.
- Keşif tüketici deneyimine göre müzik, performans ve etkinlik ağırlıklıdır.
  İşe alım, ticari hizmet ve profesyonel fırsat içerikleri dinleyici akışına
  alınmaz. Bir paylaşım ya da sosyal aktivite içindeki kaynak da aynı kurala tabidir.
- `ListenerFeedContentPolicy`, kaynak adayları sıralamadan önce denetler;
  daha önce kaydedilmiş bir sayfanın tekrarında güncel içerik uygunluğu yeniden
  doğrulanır. Uygun olmayan eski sayfa `MUSICIAN_FEED_CURSOR_INVALID` ile yenilenir.
- Müzisyen profil tamamlama kartları ve müzisyen akış tercihleri kullanılmaz.
  Konum sinyali dinleyicinin mevcut hesap şehrinden alınır. Şehir yoksa ulusal
  keşif çalışır; yeni bir tercih veya profil kaydı yaratılmaz.
- Genel `SPONSORED` kartları ve sponsor süslemeli adaylar bu sürümde kapalıdır;
  mevcut genel sponsor sözleşmesi dinleyiciye uygun ticari içerik garantisi
  taşımaz. SoundConnect duyuruları yalnız `LISTENER` hedef kitlesiyle gösterilir.

## İçerik hedef kitlesi

Medyalarda `contentAudience` alanı `MAINSTAGE` veya `BACKSTAGE` değerini taşır.
Bu alan `PUBLIC`/`UNLISTED`/`PRIVATE` depolama görünürlüğünden bağımsızdır.
Müzisyen, mekân ve grup kendi yükleme/yönetim ekranında hedef kitleyi seçer;
stüdyo medyası sahipliği gereği `BACKSTAGE`, dinleyici medyası `MAINSTAGE` olur.
Dinleyici akışı ve kaynak detayları `BACKSTAGE` medyayı göstermez. Bu, serbest
metinden otomatik iş ilanı sınıflandırması değildir: mevcut kayıtlar müzik ve
performanslar kaybolmasın diye `MAINSTAGE` olarak korunur; eski bir sektör içi
medyanın sahibi hedef kitlesini değiştirmelidir. Collab ve stüdyo kaynakları
bu seçime gerek kalmadan yapısal olarak dışlanır.

`2026-09-14-mainstage-content-audience.sql` migration'ı yeni sürümden önce
uygulanmalıdır; yerel geliştirme sırası `scripts/dev.ps1` içindedir. Migration
örnek içerik eklemez. `PUBLIC` dosyaların mevcut CDN adreslerini özel depolama
adresine dönüştürmez; hedef kitle uygulama/API içerik görünürlüğü kuralıdır.

Arama, profil medyası/parçalar, stüdyo detayları ve kaynak etkileşimlerinde aynı
dinleyici sınırı uygulanır. Overthinking paylaşımında kaynak anonim olsa bile
asıl yazarın stüdyo hesabı ve iliştirilmiş parçanın hedef kitlesi sunucuda
kontrol edilir. Özel etkinlik planları ve hayalet kimlikler yayımlanmaz.

Misafire de açık medya/profil kaynaklarında Flutter dinleyici JWT'sini gönderir.
Bu isteklere bir Bearer oturumu sunulduğunda sunucu geçersiz veya kullanılamayan
oturumu misafir yanıtına düşürmez; mevcut 401 zarfını döndürür. Headersız gerçek
misafir erişimi korunur. İstemcide kaynak isteği boyunca rol/hesap/guest değişimi
izlenir; eski yanıt deserialize edilmez. Profil medyasının birleşik ve legacy
fallback istekleri de aynı oturum bağlamını taşır; A→B→A dönüşü eski sonucu
yeniden geçerli kılmaz. Konum ve etkinlik keşfinin mevcut public taşıması sürer.

## Sıralama ve sıklık

Mevcut puanlama, yaş etkisi, gizleme/sessize alma, yazar/tür çeşitliliği ve
gerçekleşmiş görüntülemelere dayanan tekrar azaltma paylaşılır. Yoğun takip
havuzunda yeterli uygun aday varsa sayfanın yaklaşık %20'si müzik, performans
ve etkinlik içeriğine ayrılır; mevcut takip içeriği korunur. Bu bir sabit reklam
veya kart yerleşimi değildir. Küçük veya boş takip havuzunda mevcut keşif
adayları sayfayı doldurabilir; olmayan içerik üretilmez.

Parça/profil medyası aday havuzunun normal bütçesi yaklaşık %75 takip ve %25
keşiftir. Takip havuzu bu bütçeyi dolduramazsa kalan kapasite keşfe aktarılır.
Bu oran nihai ekrandaki sabit kart oranı değildir; diğer sağlayıcılar, içerik
arzı ve sayfa çeşitliliği de seçimi etkiler. Müzik keşfi şehirle kısıtlanmaz;
etkinlik ve yerel mekân önerileri mevcut şehir sinyalini kullanır.

Sayfa sınırları arasındaki yazar/tür geçmişi korunur. Teslim edilmiş veya önden
yüklenmiş kart, tek başına görülmüş sayılmaz; nitelikli görüntüleme kaydı gerekir.
Duyurular, mevcut oturum yerleşimi ve gerçekleşmiş gösterime bağlı bekleme/günlük
limitlerini kullanır; dinleyici için ayrı bir tekrar döngüsü kurulmaz.

## HTTP sözleşmesi

Tüm yollar `/api/v1/feed/listener` altında yer alır:

| İşlem | Yol |
| --- | --- |
| Akış sayfası | `GET /` |
| Kart geri bildirimi | `POST /items/{itemId}/feedback` |
| Sessize alma | `PUT /authors/{profileType}/{profileId}/mute` |
| Sessizi kaldırma | `DELETE /authors/{profileType}/{profileId}/mute` |
| Sessize alınan profiller | `GET /muted-authors` |
| Görüntüleme/etkileşim olayı | `POST /events` |

Sayfa `supportedItemTypes` ister; varsayılan boyut 20, en fazla 50 karttır.
`COLLAB`, `PROFILE_COMPLETION` ve `SPONSORED` istemci tarafından bildirilse de çıkarılır.
Yalnız bu türleri bildiren istek geçersizdir. Sayfa ve sessize alınan profil
yanıtları `private, no-store` kullanır. Rate limit, işlem kimliği, teslimat
token'ı ve geri bildirim zarfları ortak sözleşmeyi korur.
Dinleyici feedback ve telemetry uçları ayrıca teslimatın `listener-v1.0.0`
algoritmasına ait olmasını ister; hesabın eski müzisyen/mekân teslimatları bu
uçlarda kullanılamaz. Dinleyici sessize alma/kaldırma uçları `STUDIO` profilini
kabul etmez; eski stüdyo kayıtları liste sorgusunda limit uygulanmadan elenir.

## Kimlik ve gizlilik

HTTP rolü yanında veritabanından aktif, e-postası doğrulanmış, silinmemiş,
tek kişisel rolü ve gerçek profili `LISTENER` olan hesap doğrulanır. Akışı okumak
görünürlük tercihini değiştirmez: standart ve hayalet dinleyiciler tüketebilir;
hayalet veya tercihi henüz tamamlanmamış bir kişinin kimliği bu yolla yayımlanmaz.
Kaynak görünürlüğü ve paylaşım yetkileri mevcut gizlilik sınırlarında kalır.

İmzalı cursor; kullanıcı, `LISTENER` kitlesi, algoritma sürümü, dinleyici profil
kimliği, desteklenen kart türleri ve süreyle bağlıdır. Başka kullanıcı/rol/profil
cursor'ı kayıtlı sayfa aranmadan önce reddedilir. Yeni sayfa üretiminde şehir ve
geri bildirim bağlamı da kontrol edilir. Tekrar istekleri aynı teslimat defterini
kullanır; kaynak artık erişilebilir veya dinleyiciye uygun değilse eski payload
döndürülmez.

Bu özellik gerçek veritabanına örnek içerik eklemez ve ayrı bir dinleyici akış
deposu oluşturmaz. Ortak `app.feed.musician.enabled` servis anahtarı üç akışın
HTTP uçlarını birlikte kontrol eder.

## Doğrulama kapsamı

`ListenerFeedControllerTest`, `ListenerFeedViewerGuardTest`, `ListenerFeedServiceTest`,
`ListenerFeedMixerTest`, `ListenerFeedPersonalizationTest`,
`MusicianFeedListenerTelemetryServiceTest` ve ortak cursor/feedback/muted-list
testleri rol izolasyonu, gizlilik tercihini koruma, tamamlamanın dışlanması,
uygunluk politikasının ilk üretim ve tekrar yolları, teslimat kanıtı ve sıralama
dengesini kapsar. Çalıştırma sonuçları oturumun doğrulama kaydında tutulur.
`MusicianFeedMutedAuthorsPostgresTest`, eski stüdyo kayıtlarının gerçek PostgreSQL
sorgusunda sayfa boyutunu veya cursor devamını bozmadan elendiğini de doğrular.
