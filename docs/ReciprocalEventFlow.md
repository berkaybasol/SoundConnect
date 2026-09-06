# Mekan kaynaklı etkinlik ve profil görünürlüğü

## Güncel ürün kararı — etkinlik bazında göster/gizle, 2026-09-06

Etkinliği yalnız mekan sahibi oluşturur. Katılım onayı ve profil görünürlüğü
ayrıdır. Genel Haftalık Takvim anahtarı kaldırıldı. Görünürlük için ikinci bir
ayar veya ayarlara yönlendirme yoktur.

- Sadece katılımı kabul et: mekan etkinliğinde sanatçı/grup profil bağlantısı açılır.
  Etkinlik o profilin takviminde gösterilmez.
- Katılımı kabul et ve “Profilimde de göster” seç: profil bağlantısı açılır,
  etkinlik ilgili profilin takviminde de yayınlanır.
- Bağlı sanatçı/grup: bağlantı baştan kullanılabilir. Gösterim davetini kabul
  etmek ilgili profil takviminde yayınlar, ret bağlantıyı kaldırmaz.
- Katılımı reddedilen etkinlikte bağlantı kurulmaz ve profil yayını yapılamaz.
  Etkinlik başlamamışsa Reddedilen Etkinlikler'den ayrı yeniden değerlendirme
  işlemiyle katılım onaylanabilir. Etkinliğin içeriği değişmez.
- Katılımı doğrulanmış etkinlikler “Etkinliklerim” bölümünden sonradan
  gösterilebilir veya gizlenebilir. Gösterim daveti reddedilmiş bağlı etkinlik
  de sonradan gösterilebilir. Katılım onayı, mekan kaydı ve profil bağlantısı
  bu işlemden etkilenmez.
- Bekleyen davetlerin kararı önce Etkinlik Davetleri'nden verilir.
- Seçilen tarih penceresinde görünür etkinlik yoksa profil takvimi hiç yer kaplamaz.
  Aktif Mekanlar bölümü bağımsızdır ve korunur.

## Grup ve üye ayrımı

Grup profilini yalnız güncel aktif kurucu yönetir. Grup profilinde gösterim
kararı üyelerin kişisel profillerine otomatik yayın yapmaz. Aktif üye kendi
kişisel “Etkinliklerim” listesinden katılımı doğrulanmış grup
etkinliğini kendi profilinde gösterebilir veya gizleyebilir. Grup profilini
gizlemek üyenin kendi seçimini değiştirmez ve üyenin seçimi grup profilini
değiştirmez. Etkinliğin grup katılımı doğrulanmış olmalıdır.

Ayrılma veya üyelikten çıkarılma kişisel grup etkinliği yayınlarını gizler.
Sonradan yeniden katılım eski görünürlük kararlarını canlandırmaz. Yeniden
gösterim için üyenin tekrar seçim yapması gerekir. Grubun silinmesi veya
etkinliğin silinmesiyle geçerli olmayan kayıtlar yayınlanmaz.

## Ekranlar

- Etkinlik detayındaki onaylı müzisyen bağlantısı, oturum sahibi aynı müzisyense
  kendi profil ekranını açar. Diğer kullanıcılar public profili görür. Eşleşme
  kullanıcı adıyla değil doğrulanmış kullanıcı/profil kimlikleriyle yapılır.
  Onaysız isim bağlantıya dönüşmez. Grup bağlantısının mevcut public rotası korunur.
- Mekan: Yönetim Paneli → Etkinlik Yönetimi. Oluşturma, geçmiş ve silme korunur.
  Yönetim listesi Bu Haftaki Etkinlikler / Gelecek Etkinlikler / Geçmiş Etkinlikler
  olarak ayrılır. İleri tarih seçilebilir. Oluşturma ekranında bugün + 6 günün
  dışındaki tarihler için mekan profilinde görünmeye başlanacak gün belirtilir.
  Ek onay penceresi yoktur. Hesap etkinlik tarihi eksi 6 takvim günüdür.
  Public profilin mevcut tarih filtresi korunur, ayrı zamanlanmış yayın işi yoktur.
- Müzisyen/grup: Yönetim Paneli → Etkinlik Yönetimi. Mekan bağlantı yönetimi
  gibi üç seçenekli alt menü açılır: Etkinlik Davetleri / Etkinliklerim /
  Reddedilen Etkinlikler. Bildirimden giriş doğrudan ilgili davetlere gider.
- “Etkinliklerim” doğru kişisel/grup hedefiyle onaylanmış
  etkinliklerin görünürlüğünü yönetir. Kişisel listede uygun grup
  etkinlikleri de yer alır, hiçbiri yeni üyeye otomatik görünür atanmaz.
- Etkinliklerim içinde Bu Haftaki / Gelecek / Geçmiş bulunur. Tarih ayrımı ve
  toplam kayıt sayısı sunucuda hesaplanıp sayfalanır. Her giriş güncel listeyi okur.
  Bekleyen gösterim yazması sırasında dönem değiştirilmez.
- Geçmişteki görünür kayıt gizlenebilir. Gizli geçmiş kayıtta yeniden gösterme
  düğmesi yoktur. Katılım geçmişi kaybolmaz. Public haftalık takvim tarih penceresi
  değişmediği için bugün bitmiş bir kayıt gün sonunda pencereden çıkabilir.
- Davet kararları aynı ekranda tek seferde işlenir. Yazma sürerken yenileme ikinci
  bir karar doğurmaz. Kayıp yanıt veya hata sonrası yazma tekrarı yerine güncel
  sunucu durumu okunur. Başlama anında cevaplanmamış davetler salt okunur olur.
- Ayarlardaki etkinlik/takvim anahtarı ve eski ayar yönlendirmeleri kaldırıldı.
- Bilgi penceresi katılım ile profil yayını ayrımını ve sonradan değiştirme
  imkanını anlatır. Kullanıcı metinlerinde “anahtar” veya ikinci ayar yoktur.

## API ve güvenlik

- GET `/api/v1/user/event-profile-publications`
  Parametreler: `targetType=MUSICIAN|BAND`, `targetId`, `page`, `size`,
  `period=ALL|CURRENT|FUTURE|PAST`.
- POST `/api/v1/event-performer-requests/{id}/reconsider`
  Reddedilen davet için açık `showOnProfile` boolean seçimi gerektirir. Eski accept
  ucu reddedilmiş kararı değiştiremez. Yeni karar için sunucu saatine göre etkinlik
  henüz başlamamış olmalı. Ayrıntılar `EventManagementApi.md` belgesindedir.
- PUT `/api/v1/user/event-profile-publications/{eventId}`
  Gövde: `targetType`, `targetId`, `visible`, `version`.
- Hedef/etkinlik kimlikleri, sahiplik, güncel kuruculuk/üyelik, katılım ve
  sürüm kontrolü sunucuda doğrulanır. İstemci kontrolleri yetki yerine geçmez.
- Göster/gizle versiyonludur ve hız sınırına tabidir. Aynı kararı tekrar
  göndermek yeni bir durum üretmez. Eski sürümle farklı tercih çatışır.
- Davetin ilk kabulündeki yayın tercihi değiştirilemez bir karar kaydında
  saklanır. Sonraki göster/gizle tercihleri bu geçmiş kararı değiştirmez.
  Eski kabul isteğini tekrar göndermek sonradan gizlenmiş etkinliği açmaz.
- Kayıp yanıt/çatışmada istemci güncel durumu okur, otomatik yazma tekrarı yapmaz.
- Oturum/hedef değişirse eski sonuçlar atılır. Gösterim kullanıcıya ancak
  sunucu doğrulaması sonrası güncellenmiş olarak sunulur.
- Eski global takvim ayarı API'leri emekliye ayrıldı. Yeni görünürlüğü
  değiştiremezler. Public takvim uçları geriye uyumlu yanıt alanlarını korur.

## Veri geçişi ve dağıtım sırası

Üç seçenekli yönetim / yeniden değerlendirme revizyonu için yeni migration yoktur.
Backend yeniden başlatma ve güncel istemci yeterlidir. Aşağıdaki migration önceki
etkinlik bazlı yayın geçişine aittir ve yerel test veritabanında zaten uygulanmıştır.

Migration: `scripts/db/2026-09-06-event-profile-publications.sql`.

Bu sürüm çalıştırılmadan önce backend yazmaları durdurulmalı, mevcut veritabanı
yedeklenmeli ve script uygulanmalıdır. Veritabanı sıfırlanmaz. Script
transaction içinde çalışır ve once-only marker ile tekrar çalıştırıldığında
sonraki kullanıcı tercihlerini ezmez.

Eski genel ayarı kapalı kişisel/grup etkinlikleri gizli kalır. Eski grup/üye
birleşiminde gerçekten yayına uygun olan tercihler korunur. Gizli profiller
ve yeni üyeler için otomatik kişisel yayın kaydı oluşturulmaz. Eski davetin
ilk kararı, görünürlük dondurulmadan önce ayrı alana kaydedilir.

## Güncel doğrulama ve yerel veri geçişi — 2026-09-06

- Flutter tüm proje: 1.453/1.453 test başarılı, tüm proje statik analizi temiz.
  Yönetim/davet ekranları iki ek gerçek-widget görsel testiyle render edildi.
- Backend geniş 222 test ve son 90 test koşusu başarılı. Son koşudaki üç ek
  senaryoyla 225 farklı ilgili test doğrulandı. Gerçek PostgreSQL migration,
  yarış/üyelik yaşam döngüsü ve Redis hız sınırı testleri dahil. Hata/atlama yok.
- Kullanıcı backend'i durdurdu ve geçişe açıkça izin verdi. Backend portu kapalı
  ve yalnız işlem yapmayan pgAdmin bağlantısı açıkken custom-format yedek alındı.
  Yedek ayrı prova veritabanına geri yüklendi. Gerçek veri ile aynı olduğu
  doğrulandı, SQL iki kez çalıştırıldı ve her sonuç beklentiyle karşılaştırıldı.
- SQL asıl yerel `soundconnectdb` veritabanına 6 Eylül 2026 01:31 TRT'de tek
  transaction ile uygulandı. Önceden var olan 84 tablonun tüm satırlarını kapsayan
  normalize parmak izleri eşleşti. Yalnız öngörülen yayın/sürüm/karar alanları
  değişti. Etkinlikler, katılım onayları, kullanıcılar, bağlantılar, eski ayarlar
  ve bildirim/outbox verileri korundu. Veritabanı sıfırlanmadı.
- İki etkinlik, iki onaylı katılım ve sıfır profil yayını doğrulandı. M-T1/M-T2'nin
  ilk yayın seçimleri false/true olarak ayrı kayıtta korunuyor, eski ayar kapalı
  olduğu için M-T2 kendiliğinden yayınlanmıyor.
- Yedek Git depolarının dışında tutuluyor:
  `../../.local-backups/event-profile-publications-20260906/before.dump`.
  Boyut 1.414.812 bayt. SHA-256:
  `66EE217FF1B686DB964EB58F2C6439E4318D41E9FAB9C7B9B517025587EB4A74`.
  Kullanıcı verisi içerir, paylaşma veya sürüm kontrolüne ekleme.
  Yalnız bu iş için oluşturulan geçici prova veritabanı kaldırıldı, yedek korunuyor.
- Uygulanan SQL SHA-256:
  `FC6EF31ECEFF197F3D5CDBBEB71C3CA0C0BAD064381BD32C7B520077F0C2014B`.
- Backend veya telefon uygulaması ajan tarafından başlatılmadı, APK kurulmadı.
  Kullanıcı güncel backend'i başlatıp Flutter Hot Restart yapmalı. Cihaz üstü
  kabul testi henüz tamamlanmadı, sıra `EventManualTestProgress.md` içinde.

Geri dönüş gerekirse backend yazmaları tekrar durdurulup yedek ve o andaki
veriler değerlendirilmelidir. Yedeği doğrudan geri yüklemek geçişten sonra
yapılan işlemleri kaybettirir. Eski backend kodunu tek başına başlatmak yeni
grup/üye yayın izinlerinin gizliliğini garanti etmez.

Aşağıdaki kayıtlar önceki ürün aşamalarının tarihsel test sonuçlarıdır.

## Katılım ve profil yayını ayrımı doğrulaması — 2026-09-05

- Flutter tüm proje: 1.150/1.150 test başarılı; tüm proje statik analizi temiz. Katılım/gösterim kartları, varsayılan kapalı ve seçili grup örnekleriyle gerçek Flutter ekranlarından render edilip görsel olarak kontrol edildi.
- İstemci regresyonları takvimden bağımsız panel/bildirim erişimini, doğru kişisel/grup hedefini, aktif kurucu/oturum doğrulamasını, eski sunucuda kabul POST'unun engellenmesini, varsayılan/açık boolean gövdesini, kartlar arasında tercih taşınmamasını ve eski/eşzamanlı callback'lerin karar çoğaltmamasını kapsar.
- Backend ilgili geniş regresyon grubu: 37 test sınıfında 276 test başarılı; başarısız test, yürütme hatası veya atlama yok. 7 sınıfta 84 gerçek PostgreSQL testi dahildir.
- HTTP/controller sözleşmesi 18/18, servis birim testleri 25/25, tek yönlü etkinlik/tercih PostgreSQL testleri 35/35, profil görünürlük migration testleri 8/8 ve korunmuş reciprocal şema uyumluluk testleri 11/11 başarılıdır.
- Testler varsayılan kapalı yayın tercihini, açık boolean doğrulamasını, bağlı/bağlantısız kişisel ve grup akışlarını, grup/üye genel anahtarlarının bağımsızlığını, aynı/farklı tercih yarışlarını, eski izinlerin korunmasını ve outbox hatasında atomik geri almayı kapsar.
- Eski SQL dosyasının tekrar çalıştırılmasının bilinçli `false` tercihini yayına dönüştürmemesi gerçek PostgreSQL üzerinde doğrulandı. Güncel dosya SHA-256: `FAF2C46FDEF13A083A7A6A343C16434DF8B6E98D5C70240BFDB51D21C8D40D98`.
- Bu aşamada yeni şema migration'ı veya canlı veri işlemi yapılmadı. Cihaz üstü kabul testi güncel backend yeniden başlatıldıktan sonra ayrıca yapılmalıdır.

## Tarihsel: takvimle davet girişini engelleyen UI doğrulaması — yerini yukarıdaki ayrı kararlara bıraktı, 2026-09-05

Bu bölüm önceki uygulama aşamasının kaydıdır. Takvim kapalıyken davet sayfasını engelleyen kural ve bu aşamaya ait “yalnız Hot Restart” dağıtım notu artık geçerli değildir. Ayarların ve yönetim paneli girişlerinin konumu korunmuştur.

- Haftalık Takvim, Hesap bilgileri altındaki Etkinlik Ayarları bölümüne taşındı. Aktif Mekanlar hakkındaki ek açıklama kaldırıldı; bölümün kendisi korunur.
- O aşamada Etkinlik Davetleri yönetim panelinde Mekan Bağlantılarını Yönet'in hemen altına yerleştirildi. Kişisel ve grup girişleri, bildirimler dahil aynı hedefin anahtarını doğruluyordu. Kapalı veya doğrulanamayan tercihte davet ekranı/verisi yüklenmiyordu; bu engelleme artık kaldırılmıştır.
- Ayarlara dönüş, yanlış profil/grup, oturum değişimi, eşzamanlı tercih değişimi, uygulamaya dönüş, tekrarlanan dokunma ve yalnızca ilgili ekranın/pencerenin kapatılması regresyon testleriyle doğrulandı. Anahtarı açmak hiçbir daveti otomatik onaylamaz.
- Bu tarihsel aşamada Flutter tüm proje: 1.120/1.120 test başarılı; tüm proje statik analizi temiz. Gerçek Flutter widget'larından ayarlar, yönetim paneli ve kapalı anahtar yönlendirmesi render edilip görsel olarak kontrol edildi.
- Bu tarihsel düzenlemede backend kaynakları, backend testleri, SQL scriptleri ve veritabanı değiştirilmemişti. O UI değişikliği için önceki backend sürümü çalışıyorsa yalnızca Flutter Hot Restart yeterliydi; güncel katılım/yayın ayrımı için backend de yeniden başlatılmalıdır. Telefon üstü manuel kabul testi ayrıca yapılmalıdır.

## Tarihsel: yalnız mekan oluşturmasına geri dönüş doğrulaması

- Flutter tüm proje: 1.075/1.075 test başarılı; tüm proje statik analizi temiz.
- Backend ilgili regresyon grubu: 37 test sınıfında 236 test başarılı; hata ve atlama yok. 7 sınıfta 61 gerçek PostgreSQL testi, korunmuş migration testleri 11/11 ve yeni tek yönlü akış PostgreSQL testleri 15/15 dahil.
- Müzisyen oluşturma/ters mekan isteği uç noktaları kaldırıldı; kalan mekan oluşturma/silme uç noktaları müzisyen rolünü reddeder. Eski MUSICIAN etkinlik kimliğine yorum/beğeni ekleme yolu da kapatıldı ve test edildi.
- Gerçek Flutter widget'larından mekan takvimi ve afişsiz onay ekranı render edildi, görsel olarak kontrol edildi.
- Kapatılan çift yönlü akışa ait testler kaldırıldı; yerine yalnızca mekanın oluşturabildiğini, kapalı kişisel/grup anahtarıyla onay verilebildiğini ve eski kökenlerin yayınlanmadığını doğrulayan regresyon testleri eklendi.

Otomatik testler cihaz üstü kabul testinin yerine geçmez. Backend yeniden başlatıldıktan sonra iki hesapla manuel test ayrıca yapılmalıdır.

## Tarihsel: 2026-09-05 iki yönlü yerel geçiş doğrulaması

- Backend: ilgili akışları kapsayan 37 sınıfta 277 test başarılı; 8 PostgreSQL sınıfında 66 test dahil, hata/atlama yok. Reciprocal geçiş testleri 11/11.
- Flutter: tüm proje testleri 1091/1091; tüm proje analizi temiz.
- Yerel `soundconnect-local-postgres-1` / `soundconnectdb` için backend kapalıyken custom-format yedek alındı. Yedek ayrı bir veritabanına başarıyla geri yüklendi; yeni SQL bu kopyada iki kez çalıştırıldı.
- Aynı SQL asıl yerel veritabanında tek transaction ile uygulandı. Dört eski etkinlik doğru mekan/sahip bilgileriyle taşındı. Önce/sonra karşılaştırmasında eski etkinlik alanları, katılım istekleri, kullanıcılar, kişisel/grup takvim tercihleri, bağlantı sayıları ve bildirim/outbox sayıları değişmedi.
- Prova veritabanı kaldırıldı; doğrulanmış yedeğin ek kopyası `../.local-backups/soundconnect-pre-reciprocal-20260905-050955.dump` altında tutuluyor. Bu konum Git depolarının ve Gradle `build` dizininin dışındadır; `gradle clean` yedeği kaldırmaz. Yedek kullanıcı verisi içerir, paylaşma veya sürüm kontrolüne ekleme.
- Yedek SHA-256: `985A440BD97928C9D96472AEFFBD193EFE248AE9D34BCFC4B950D59B4EF16D8C`.
- Uygulanan SQL SHA-256: `E16BD60E930AF2EFFB984AB36BDC62B89C991AD3C86A5ECB7979C6654965A49B`.
- Backend veya telefon uygulaması ajan tarafından başlatılmadı; APK kurulmadı. Kullanıcının backend başlangıcı, Flutter Hot Restart ve iki hesapla cihaz üstü kabul testi henüz bekliyor. Bu kayıt üretim ortamına dağıtım veya cihaz testlerinin tamamlandığı anlamına gelmez.
