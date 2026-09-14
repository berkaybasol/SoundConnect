# Stüdyo Backstage akışı

## Ürün kararı

Stüdyo hesabı, takip ettiği müzik çevresinin yayınlarını okur; çalışabileceği
müzisyen/grupları ve açıkça stüdyo arayan Collab ilanlarını keşfeder. Kullanıcının
onayladığı %20–30 aralığı için ilk sürümde arz varsa yaklaşık %25 korunan pay
seçilmiştir. Mevcut ortak akış, kartlar, gerçek detaylar, etkileşim, arama ve alt
bar kullanılır. Yeni profil tamamlama, paylaşım oluşturucu veya yönetim özeti yoktur.

Bu akış, mevcut stüdyo profil/oda/backline/rezervasyon ürünlerinden ayrıdır.
Rezervasyon ve envanter değişiklikleri otomatik sosyal yayına dönüşmez. Gerçek
DB'ye örnek içerik eklenmez. Kayıt/prova/mix/mastering veya uzaktan çalışma için
yapılandırılmış eşleşme verisi bulunmadığından bu özellikler varmış gibi sunulmaz.

## Kaynaklar ve sıralama

- Takip edilen uygun yayınlar ve sosyal hareketler ortak kaynaklardan gelir.
  Takip edilen stüdyo, mekân ve dinleyici içeriklerinin mevcut görünürlüğü korunur.
- Takip dışı profil/parça/profil medyası keşfi müzisyen ve gruplara yönelir.
  Şehir eşleşmesi, sanatçının mevcut fırsat şehri tercihini; bu boşsa hesap şehrini
  kullanır. Gruplarda uygun aktif üyeler değerlendirilir. Özel tercih ve üye
  şehirleri payload'a eklenmez. Eşleşme bir talep veya hizmet ihtiyacı iddiası değildir.
- Parça/profil medya aday havuzunun yarısı takibe ayrılır. Kalan kapasite önce
  yerel, sonra diğer şehirlerden sanatçılarla dolar; boş takip kapasitesi keşfe
  aktarılır. Bunlar nihai ekran yüzdeleri değildir. Fotoğraflar normal sıralamada
  kalır; müzik/video/ses sanatçı keşfinde önceliklidir.
- Collab keşfinde takip dışı ilanlar, sorgu limiti uygulanmadan `wantedType=STUDIO`
  koşulundan geçer. Önce yerel, sonra diğer şehirlerden uygun açık ilanlar gelir;
  kullanılmayan takip bütçesi bu havuzlara aktarılır. Stüdyonun enstrüman tercihi
  kullanılmaz. Şehir eşleşmesi ek puandır; ülke çapında uygun ilanlar da devam eder.
- Takip edilen birinin başka türdeki ilanı sosyal yayın olarak kalabilir; stüdyo
  fırsat payını doldurmaz ve stüdyoya özgü eşleşme puanı almaz. Örneğin
  `MUSICIAN + SOUND_ENGINEER`, stüdyonun başvurabileceği bir talep değildir.
  Başvuruda mevcut Collab aktör/talep türü eşitliği korunur.
- Takip edilen etkinlikler normal sosyal akışta kalır. Takip dışı yerel etkinlik,
  stüdyoda genel keşif havuzuna girer; sanatçı/stüdyo talebi rezervasyonunu doldurmaz.
- Overthinking ve masa paylaşımlarının düşük sıklığı ve aralarında normal içerik
  bulunması korunur. Global Mainstage havuzu doğrudan akışa eklenmez.

En az dört normal seçim slotunda, uygun arz varsa `floor(slots/4)` pay korunur.
Bu pay açık stüdyo talepleri ve **takip dışı** müzisyen/grup profil/parça/video/ses
keşfiyle dolar. Takip edilen sanatçının sıradan yayını yeni sanatçı keşfi sayılmaz.
Uygun talep varsa ayrılan pay içinde en az bir talep; en az iki ayrılmış slot ve
sanatçı arzı varsa en az bir sanatçı keşfi korunur. Diğer uygun kartlar ortak
puanlama/çeşitlilik kurallarıyla seçilir. Duyuru veya sponsor eklenmesi bu alt
korumaları sayfanın sonundan atamaz.

%25 bir üst sınır veya sabit her-dördüncü-kart yerleşimi değildir. Az takip/ilan
arzında diğer uygun kaynaklar devam eder; olmayan talep için boş kart, yapay
içerik veya tekrar üretilmez. 1–3 kartlık küçük sayfalarda zorunlu rezervasyon yoktur.
Kaynak havuzları sınırlıdır; havuz dışındaki her içeriğin bulunması garantisi yoktur.

## Ortak davranışlar

Mevcut gizle/daha az göster/sessize al/şikâyet, beş organik kartlık çeşitlilik
geçmişi, gerçek son görüntülemelerde yumuşak tekrar azaltma, beğeni/yorum ve Collab
kaydetme işlemleri kullanılır. Duyurular `STUDIO` hedef kitlesiyle ortak seçim,
bekleme, günlük sınır ve yerleşim planını izler. Sponsor entegrasyon sözleşmesi
korunur; bu çalışma yeni reklam kampanyası veya sponsor kaynağı bağlamaz.

## API ve kimlik

Algoritma `studio-v1.0.0`; mevcut şema sürümü ve limit/cursor/supportedItemTypes
sözleşmesi kullanılır. Yollar `/api/v1/feed/studio` altında:

| İşlem | Yol |
| --- | --- |
| Akış | `GET /` |
| Kart geri bildirimi | `POST /items/{itemId}/feedback` |
| Sessize alma/kaldırma | `PUT/DELETE /authors/{profileType}/{profileId}/mute` |
| Sessize alınanlar | `GET /muted-authors` |
| Görüntüleme/etkileşim olayı | `POST /events` |

HTTP rolüne ek olarak güncel DB kişisel rol/profil kümesi, aktif ve e-postası
doğrulanmış silinmemiş hesap, stüdyo profili ve sahipliği doğrulanır. Bekleyen,
reddedilmiş ve karma kişisel rollü hesaplar kabul edilmez. Şehir mevcut stüdyo
profilinden okunur; müzisyen tercih kaydı yaratılmaz. Profil tamamlama istemci
yetenekleri, servis, sağlayıcı ve mixer üzerinden dışlanır.

Cursor ve teslimatlar stüdyo audience/algoritma ve profil bağlamını kullanır.
Başka role ait cursor ve geri bildirim/telemetri makbuzu stüdyo yolunda kabul
edilmez. Frontend kullanıcı/token/rol değişiminde önceki akış sonucunu ayırır.
Mevcut ortak rate limit, replay, kaynak görünürlüğü ve moderasyon uygulanır.
Dinleyicinin stüdyo/Collab/BACKSTAGE sınırları değişmez.

## Doğrulama

Bu değişiklik için kayıtlar workspace `.local-verification/studio-feed-20260914`
altındadır. Kaynak ve test doğrulaması ile telefonda kurulu APK/çalışan API sürümü
ayrı değerlendirilir. Bu özellik için yeni migration gerekmiyor.

Tam Flutter koşusu 5.020 başarılı / 2 isteğe bağlı PNG atlaması, analiz temizdir.
Backend ilgili regresyonunda en son paket sonuçlarının birleşimi 189 paket /
1.532 başarılı / 0 başarısız / 0 hata / 0 atlamadır. İlk geniş koşudaki 1.525
testin altısı ID'siz karma rol fixture'ı nedeniyle başarısızdı; yalnız test
kurulumu düzeltildi. Controller'ın 24 testi ve ek 7 sponsor testi ikinci koşuda
geçti. Aynı controller testleri iki kez sayılmaz; iki koşu arasında üretim
kodu değişmedi. Bu, tüm backend testleri veya yük testi sonucu değildir.

Sorgu testleri izole PostgreSQL üzerinde yanlış Collab türlerinin limitten
önce elenmesini, yerel/ülke çapı geri dolumu, özel şehir verilerinin payload'a
taşınmamasını ve etkinlik önceliğini doğrular. Mixer testleri takip yoğunluğu,
iki yararlı kaynak arasındaki alt koruma, küçük/seyrek sayfa, modül sıklığı,
duyuru ve native sponsor tekilleştirmesini kapsar. Kaynak hash'leri, orijinal
JUnit XML'leri ve koşu sonuçları kanıt dizinindedir.

Sonraki kullanıcı talebiyle 14 Eylül akşamı normal API güncellendi (PID `444112`,
8080 readiness UP), normal APK `emulator-5554` üzerine kuruldu. Exact dev.ps1
Import-DotEnv davranışı ve JWT eşitliği korundu; migration/seed yapılmadı.
Emülatör açılışı ve şehir listesi bağlantısı doğrulandı. Fiziksel telefon
güncellenmedi; oturum açılmış stüdyo cihaz testi yapılmadı. Dağıtım kanıtları
workspace `.local-verification/studio-feed-deploy-20260914/` altındadır. Güncel
devir belgesi frontend `docs/session-handoff-20260914-studio-feed.md` dosyasıdır.
