# Birbirine bağlı profil modülleri denetimi — 7 Eylül 2026

Güncel ek: kullanıcının tüm bağlı koşulları yeniden inceleme isteğiyle ikinci,
daha geniş durum-geçiş denetimi yapıldı. Bu rapordaki 2306/402 ilk turun
sonuçlarıdır. Yeni karşı-örnekler, onaylanan ürün kuralları ve son doğrulama
`ConnectedModulesScenarioMatrix.md` içinde ayrı takip edilir.

## Kapsam ve korunmuş veriler

Grup/müzisyen profilleri, mekan bağlantıları ve başvuruları, grup üyeliği/davetleri,
bildirimlerin kayıt ve yönlendirme akışı incelendi. Beğenilen mini üye kartı
grup/müzisyen mekanlarına ve mekanın aktif sanatçılarına ortak bileşen olarak
uygulandı. Üye unvanı isteğe bağlı kaldı. Profil sahipliği yönlendirmeleri korundu.

Çalışan backend durdurulmadı. Üretim derleme klasörü kullanılmadı. Gerçek davet,
üyelik, etkinlik, tercih veya bağlantı değiştirilmedi. Mevcut kirli çalışma
ağacındaki ilgisiz düzenlemeler geri alınmadı. Commit/push yapılmadı.

## Kapatılan sorunlar

- Başvuru kabul/ret işlemlerinde başarısız Result nesnesinin başarı gibi
  gösterilmesi ve liste okuma hatasının boş listeye dönüşmesi düzeltildi.
- Mekan tarafından gruba gönderilen bağlantı isteğinin müzisyen gibi
  değerlendirilmesi düzeltildi. Kimliği requestByType değil bandId belirliyor.
- Hesap değişikliği, geç dönen liste/işlem yanıtları, üst üste işlemler ve
  sayfa yenileme çakışmaları için oturum/istek korumaları eklendi.
- Bildirim okuma/silme işlemlerinin eski yenileme yanıtıyla geri alınması,
  silinen bildirimin sayfalama ile tekrar görünmesi ve yerel okuma sonrasında
  sıralamanın bozulması düzeltildi.
- Gizlilik açısından hassas bildirimlerin canlı gönderimi öncesinde güncel
  kimlik çözümlemesi kullanılıyor. Grup/bağlantı bildirimleri gereksiz yeni
  veritabanı işlemi açmıyor.
- Grup üyeliği ve bağlantı değişikliklerine ait uygulama içi bildirimler aynı
  veritabanı işlemi içinde kalıcı olarak kaydediliyor. Başarısız işlem hayalet
  bildirim bırakmıyor. Anlık soket bildiriminin başarısızlığı kayıtlı bildirimi
  kaybettirmiyor.
- Zaten sona ermiş üyeliği yeniden çıkarma isteği tekrar bildirim üretmiyor
  veya kişisel gösterim sürümlerini gereksiz değiştirmiyor.
- Davet edilecek hesap aktif, doğrulanmış ve müzisyen profiline sahip olmalı.
- Çift sanatçı kimliği, bağlı çifte yeniden başvurma, eşzamanlı oluşturma/
  kabul/ret ve eski ayrılmış bağlantıyla yeni bağlantıyı kaldırma korundu.
- Bağlantı isteği mesajının 255 karakterlik mevcut depolama sınırı API
  doğrulamasına taşındı.
- Üç türün yönetim listelerine SQL tarafında yön filtresi, 20 kayıtlık sayfalar,
  yeniden deneme, yenileme ve liste kaymasına karşı baştan yükleme eklendi.
  Avatarlar topluca getiriliyor. Mekan listesi her sanatçı için ayrı tam
  profil isteği yapmıyor.

## Kullanıcının onayladığı ürün kararları

1. Eski grup daveti yeni daveti onaylayamaz. Her yeniden davette farklı bir
   invitationId üretilir. Kimliği olmayan eski bildirim veya eski ekran
   karar veremez. Kullanıcı güncel daveti açıkça açarak yeni onay verir.
2. Üç grup oluşturma sınırında yalnız aktif kurucu olunan gruplar sayılır.
   Başkasının grubuna üyelik oluşturma hakkını azaltmaz. Sunucu son kararı
   verir ve aynı hesabın eşzamanlı oluşturma işlemlerini sıraya alır.

## Doğrulama

Tam Flutter paketi **2.306/2.306** geçti. Son açık mounted koruması düzenlemesi
sonrasında 34 odaklı test de tekrar geçti. `dart analyze lib test` sonucunda
**No issues found**. Her iki depoda `git diff --check` başarılı.

Backend final paketi **402/402** geçti, **0 hata, 0 atlanan test**.
Gerçek, geçici PostgreSQL bağlantı yarış testleri ve davet kimliği geçişi
testleri de bu sonuca dahil. Son Gradle koşusu BUILD SUCCESSFUL.
Önceki koşularda test kurulumuna ait iki sorun çıktı (iç içe Mockito stubbing,
SQL fixture içinde eksik kullanıcı satırı). Üretim kodunu bu testleri geçirmek
için gevşetmeden fixture'lar düzeltildi ve paketin tamamı yeniden çalıştırıldı.

Kanıt dosyaları:

- Frontend/build/connected-audit-final-full-tests.log (2.306 test)
- Frontend/build/connected-final-guard-tests.log (34 test)
- Frontend/build/connected-audit-final-analyze.log (temiz analiz)
- .local-verification/connected-audit-verified-tests.log (son backend koşusu)
- .local-verification/connected-audit-build/test-results/test/ (JUnit XML)

Yukarıdaki Frontend yolları SoundConnect-Frontend dizinine, .local-verification
yolları ise çalışma alanının köküne göredir. Backend main/test kaynakları normal
`build` klasörü yerine connected-audit-build içine derlendi. Derleme başarılı,
depoda önceden bulunan Lombok/mapper/deprecation uyarıları devam ediyor.

Salt okunur mevcut veritabanı kontrolünde şu altı tutarsızlık sayısı sıfırdı:
çift ACCEPTED çiftleri, çift PENDING çiftleri, iki/hiç sanatçı kimliği,
aktif eski MANAGER rolü, onaylı isteğe karşılık profil bağlantısı bulunmaması,
profil bağlantısına karşılık onaylı istek bulunmaması. Kontrol gerçek kullanıcı
verilerini değiştirmedi, diğer veritabanları için garanti oluşturmaz.

Mini kartın gerçek yazı tipli önizlemesi:
SoundConnect-Frontend/build/profile-connection-mini-cards.png.
320dp ve 1x/2x/3x yazı ölçeğinde açık/koyu tema kart testleri geçti.

## Uygulamaya alma — henüz yapılmadı

1. Backend kullanıcı tarafından durdurulmalı ve güncel veritabanı yedeği alınmalı.
2. scripts/db/2026-09-07-band-invitation-identity.sql uygulanmalı.
   Mevcut bekleyen davetlere kimlik ekler, üyelik/onay/etkinlik durumu değiştirmez.
   Yerel dev.ps1 ön-Hibernate geçiş listesine kaydedildi. IDE'den doğrudan
   çalıştırmak tek başına eski bekleyen davetleri doldurmaz.
3. scripts/db/2026-09-07-artist-venue-request-pages.sql ayrı autocommit oturumunda
   uygulanmalı. CONCURRENTLY indeksleri transaction içine alınmamalı.
4. Yeni backend ve Flutter kodu birlikte kullanılmalı. Eski istemci sürümsüz
   onay gönderirse güvenlik gereği reddedilir. Veritabanı sıfırlaması gerekmez.
5. Telefondaki manuel doğrulama EventManualTestProgress.md kaydından devam eder.

## Sınırlar

Bu çalışma hatasızlık veya yük testi garantisi değildir. Önemli yarışlar gerçek,
geçici PostgreSQL testleriyle doğrulansa da üretim ölçeğinde yük testi yapılmadı.
Anlık soket gönderimi best-effort, kalıcı uygulama içi bildirim kaydı asıl kaynaktır.
Eski tam liste API'leri uyumluluk için korunur. Yeni yönetim ekranları sayfalı
API'yi kullanır. Eski istemcilerin devreden çıkarılması ayrı yayın çalışmasıdır.

Grup bağlantı geçmişini aktif üyelerin okuyabilmesi, bağlantı yetkisindeki eski
MANAGER desteği ve iptal/ret/bağlantı kaldırma durumlarının aynı REJECTED değeriyle
saklanması değiştirilmedi. Bu kurallar yeni bir yetki veya bildirim ürünü
tasarlamadan sessizce değiştirilmemelidir.

Ayrıntılar: ArtistVenueConnectionAudit.md ve BandInvitationIdentityAndNotificationDelivery.md.
