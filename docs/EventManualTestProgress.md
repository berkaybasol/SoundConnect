# Etkinlik akışı — manuel test kaydı

Son güncelleme: 6 Eylül 2026. Ana akış: `ReciprocalEventFlow.md`.

## Mola kontrol noktası — M-T5 geçti, B-T1 henüz başlamadı

En güncel manuel durum budur. Kullanıcı kendi profiline yönlendirme düzeltmesini
telefonda doğruladı. M-T5 daveti reddedildi, ardından “Bu etkinliği profilimde de
göster” seçili yeniden onaylandı. Reddedilenlerden kalkması, Etkinliklerim'de ve
müzisyen profilinde tek görünür kayıt olması, mekan detayında dokunulabilir
`@bugrasahin` bağlantısı kullanıcı tarafından doğrulandı. Bu kontroller GEÇTİ.

Sıradaki B-T1 grup testi henüz başlamadı. Devam ederken gelecekteki, haftalık
pencere içindeki bir tarihle `B-T1 — Grup katılımı` oluşturulacak, sanatçı olarak
kişisel müzisyen değil Şahbaz grubu seçilecek. Önce grup sahibine gelen bildirimin
kişisel davet değil gruba yönelik metin taşıdığı kontrol edilecek. Henüz onay yok.

Kullanıcı mola sırasında başka oturumda işlem sonrası alt bildirimlerin yalnız
görsel tasarımını ortaklaştırmayı planlıyor. Bu değişiklik öncesi frontend/backend
commit ve push kontrol noktası alınıyor. Dönüşte diğer oturumun raporu ve kısa
görsel kontrol ardından B-T1'den devam edilecek. Geçen testler baştan alınmayacak.

## Güncel durak — M-T4B geçti, kendi profiline yönlendirme kontrolü

Bu bölüm en güncel durumdur. Altındaki “henüz sonuç bildirilmedi” ve sıradaki
adım ifadeleri önceki kontrol noktalarının tarihçesidir.

Kullanıcının telefonda doğruladığı kontroller GEÇTİ:

- `M-T4B — Katılım reddi` reddedildi ve Reddedilen Etkinlikler'e taşındı.
- Mekandaki etkinlik korundu. Sanatçı adı @ olmadan ve bağlantısız kaldı.
  Müzisyenin public profilinde ve Etkinliklerim → Bu Haftaki listesinde yoktu.
- Reddedilenlerden, “Bu etkinliği profilimde de göster” seçilmeden yeniden
  onaylandı. Reddedilenlerden kalktı ve mekan etkinliğinde profil bağlantısı açıldı.
- Etkinliklerim → Bu Haftaki bölümüne tek gizli kayıt olarak geldi, müzisyenin
  public takviminde görünmedi.
- Etkinliklerim'den Profilimde göster seçildi. Müzisyenin profilinde bir kez
  göründü ve mekan etkinliğindeki onaylı @ bağlantısı korundu.

Bulunan ayrı hata: `bugrasahin` kendi hesabındayken etkinlik detayından kendisine
dokununca kendi profil ekranı yerine public profil ekranı açılıyordu. Bu hata
yukarıdaki onay/yayın testlerini geçersiz kılmaz. Düzeltme yalnız istemci
yönlendirmesindedir. Backend veya veritabanı değişikliği gerektirmez.

Düzeltmenin otomatik doğrulaması: tüm Flutter paketi 1.638/1.638 GEÇTİ,
statik analiz temiz. 24 yeni regresyon kendi/başkasının profili, hızlı çift
dokunma, eksik kimlikte doğrulanmış kendi-profil sorgusu, ağ hatası ve tekrar
deneme, hesap/oturum değişimi, kapanmış/örtülmüş detay ekranı ve geri dönüp
profili yeniden açmayı kapsar. Onaysız isimler ve grup public rotası korunur.

Sıradaki telefon kontrolü: Hot Restart sonrası aynı etkinlik detayından
`@bugrasahin` adına dokun. Kendi profil ekranı ve Yönetim Paneli erişimi açılmalı.
Geri dönüldüğünde etkinlik detayı korunmalı. Başka hesaptan aynı bağlantı public
profili açmaya devam etmeli. Bu yönlendirme kontrolü henüz GEÇTİ sayılmıyor.
M-T4B oluşturma/ret/yeniden onay adımlarını baştan tekrarlamak gerekmiyor.
Ardından kalan tarih sınırı, grup/üye ayrımı ve bağlı profil senaryolarına dönülecek.

## Son tasarım revizyonu — yönetim alt menüleri ortak

Müzisyen ve grup tarafında Mekan Bağlantılarını Yönet ile Etkinlik Yönetimi
aynı ortak bileşeni kullanır. Kompakt, yalnız ikon/başlık içeren ince gradient
çerçeveli mekan bağlantısı tasarımı seçildi. Seçenekler ve yönlendirilen akışlar
korundu. Bu revizyon yalnız istemci tasarımıdır, Hot Restart yeterlidir.
Backend yeniden başlatma veya veri geçişi gerekmez. Manuel test ilerlemedi,
sıradaki adım aşağıdaki M-T4B ret ve yeniden onay kontrolüdür.
Doğrulama: tüm Flutter paketi 1.614/1.614 başarılı, statik analiz temiz. Ortak
menülerin gerçek Flutter PNG'leri kontrol edildi. Müzisyen/grup yönlendirmeleri,
üç tema, dar/yatay ekran ve tekrarlanan seçimler regresyon testleriyle doğrulandı.

## Güncel durak — üç seçenekli etkinlik yönetimi ve ret kararını değiştirme

Bu bölüm güncel durumdur. Aşağıdaki önceki tasarım/geçiş kayıtları tarihçedir.

- Yönetim Paneli → Etkinlik Yönetimi artık üç seçenekli alt menü açar:
  Etkinlik Davetleri / Etkinliklerim / Reddedilen Etkinlikler.
- Etkinliklerim içinde Bu Haftaki / Gelecek / Geçmiş, sunucu tarafında filtrelenir
  ve sayfalanır. Gizli ama katılımı onaylı etkinlikler yönetimde kalır.
- Reddedilen davet yalnız etkinlik başlamadan önce yeniden onaylanabilir.
  Katılım onayında profilde gösterim yine ayrı bir seçimdir. Etkinlik içeriği
  düzenlenemez. Başlamış davetlerde karar butonları yoktur.
- Geçmişte görünür etkinlik gizlenebilir. Gizli geçmiş kayıt için yeniden gösterme
  sunulmaz. Bu arşiv ekranı kuralıdır, katılımı veya mekan etkinliğini değiştirmez.
- Müzisyen/grup ayrımı, kurucu yetkisi ve kişisel grup üyesi tercihleri korunur.

Dağıtım: backend yeniden başlatılmalı ve güncel Flutter koduyla Hot Restart
yapılmalı. Bu revizyon yeni SQL geçişi gerektirmez. Mevcut kullanıcı veritabanına
dokunulmadı. Önceki profil yayınları migration'ı zaten uygulanmıştı.

Manuel test durumu korunuyor: M-T1 ve M-T3 katılım/göster-gizle kontrolleri GEÇTİ.
M-T4 reddedildi ve mekan yönetiminde kaldığı doğrulandı, ancak 15 Eylül tarihi
haftalık pencere dışında olduğundan public profil ret kontrolü sayılmıyor.
15 Eylül → 9 Eylül görünürlük bilgisi ve 8 Eylül'e dönüş kontrolü GEÇTİ.
`M-T4B — Katılım reddi` için kullanıcı henüz sonuç bildirmedi.

Yeni manuel sıra (henüz GEÇTİ sayılmıyor):

1. Müzisyen panelinden üç seçenekli menüyü aç, her bölümün doğru kişisel profili
   gösterdiğini kontrol et. Etkinliklerim içinde üç zaman bölümünü kontrol et.
2. Haftalık pencere içinde ve başlamamış M-T4B davetini reddet. Mekanda etkinlik
   kalmalı, sanatçı bağlantısı açılmamalı. Kişisel etkinlik listesine eklenmemeli.
3. Reddedilen Etkinlikler'den aynı daveti, profilde gösterim seçili değilken
   onayla. Reddedilenlerden kalkmalı, mekan bağlantısı açılmalı, Etkinliklerim'e
   gizli eklenmeli. Kişisel public profilde görünmemeli.
4. Ayrı bir başlamamış ret kaydını profilde gösterim seçili yeniden onayla.
   Tek kayıt görünmeli. Gizle/göster katılım bağlantısını değiştirmemeli.
5. Başlamış/geçmiş ret kaydı listede okunabilmeli ama onaylanamamalı. Geçmiş
   katılımı onaylı etkinlik Geçmiş altında korunmalı.
6. Aynı kuralları Şahbaz grubunun kurucusu ile tekrarla. Kişisel/grup yayın
   tercihleri birbirini değiştirmemeli. Ardından bağlı profil senaryolarına dön.

API ayrıntıları ve saat/sayfalama kuralları: `EventManagementApi.md`.

Otomatik doğrulama: son frontend tam paket 1.595/1.595 başarılı. Yeniden kararın
tek seferde işlenmesi, yazma sırasında yenileme, belirsiz ağ yanıtından sonra
salt okunur uzlaştırma, son tarih anı, kişisel/grup yetkisi ve boş ara sayfalarda
gezinme regresyonları dahildir. Gerçek Flutter menü ve dönem ekranı PNG
önizlemeleri kontrol edildi. Bu sonuçlar yukarıdaki telefon adımlarını GEÇTİ yapmaz.
Son statik analiz temiz. Sabit sunucu sıralamasını koruyan son küçük düzeltmeden
sonra ilgili 107 davet/yeniden değerlendirme testi de tekrar GEÇTİ.

Backend son doğrulama: 15 test sınıfında 208/208 başarılı, hata/atlama yok.
77 gerçek PostgreSQL testi dahildir (45 venue flow + 32 calendar). Üç JVM saat
dilimi, tam başlama/bitiş sınırı, gece yarısı, sayfa toplamları, eşzamanlı yeniden
onay, gizleme sonrası karar tekrarı ve outbox tekilleştirmesi kapsanır. Grup üyesi
yayın tercihleri sayfa başına tek toplu sorguyla okunur, N+1 sorgu yoktur.
Son çalışma izole yerel derleme çıktısındadır:
`../.local-verification/event-management-build/test-results/test`.
Derleyicinin sandbox altında paket okuyamaması, izinli izole doğrulama ile
aşıldı. Kullanıcının backend süreci veya veritabanı değiştirilmedi.

## Son kontrol — ileri tarih bilgisi geçti, sanatçı boş metni

Kullanıcı 15 Eylül seçildiğinde 9 Eylül görünürlük bilgisinin çıktığını ve
8 Eylül'e dönünce bilgi kartının kalktığını doğruladı. Bu kontrol GEÇTİ.
Sanatçı seçilmemiş etkinlikler için “Yakında açıklanacak” yerine “Belirtilmemiş”
metni kullanılıyor. Mekan profil yanıtı, etkinlik DTO'su ve paylaşım görseli
güncellendi. Bu metin katılımı beklenen gerçek sanatçı gibi bilgi ikonu üretmez.
Etkinlik düzenleme akışı eklenmedi. Bu metin revizyonu için kullanıcı backend'i
yeniden başlatmalı ve Flutter Hot Restart yapmalı. Veritabanı geçişi yok.

## İleri tarihli etkinlik ayrımı — M-T4 tanısı

M-T4 reddedildi. Kullanıcı mekan profilinde görünmediğini bildirdi. Salt okunur
DB kontrolü kaydın 15 Eylül tarihli, VENUE kökenli, mekan bağlantısı korunmuş,
REJECTED ve profil yayını false durumda olduğunu gösterdi. Mekan profili yalnız
bugün + 6 günü gösteriyor. Kullanıcı yönetim listesinde kaydın durduğunu doğruladı.
Bu gözlem veri silinmesi/ret hatası değil, haftalık pencere dışındaki tarih.
Haftalık pencere içindeki yeni kayıtla ret testinin public link/görünürlük
kontrolleri henüz yeniden yapılmadı.

Onaylanan ürün kararı uygulandı: ileri tarih seçimi açık. Mekan yönetimi
Bu Haftaki Etkinlikler / Gelecek Etkinlikler / Geçmiş Etkinlikler olarak ayrılır.
Oluşturma ekranında 7 günlük pencerenin dışındaki tarihler için gradient bilgi
kartı, etkinliğin mekan profilinde görünmeye başlayacağı tarihi belirtir.
Ek onay penceresi yok. 15 Eylül etkinliği için başlangıç 9 Eylül'dür.
Sanatçı/grup izinleri değişmedi. Backend/veritabanı değişikliği yok.

## Güncel manuel durum — M-T3 geçti, M-T4 ret onayında

Kullanıcı M-T3'ü profilde gösterim seçili onayladı ve profilinde gördü. Sonra
gizlediğinde etkinlik kişisel profilden kalktı, mekandaki tıklanabilir profil
bağlantısı korundu. Yeniden gösterince tek kayıt olarak döndü. Uygulamayı
kapatıp açınca tercih korundu, yeni onay istenmedi. Bu adımlar GEÇTİ.

`M-T4 — Katılım reddi` daveti oluşturuldu. Kullanıcı ret onay penceresinde
tasarım revizyonu istedi. Pencere koyu zemin/ince gradient çerçeve ve yalnız
“Etkinlik davetini reddetmek istiyor musunuz?” sorusuyla güncellendi. Ret henüz
manuel olarak doğrulanmadı. Hot Restart sonrası aynı M-T4 daveti reddedilecek.
Mekanda etkinlik kalmalı, sanatçı adı tıklanmamalı, kişisel profilde ve Güncel
Etkinliklerim listesinde bulunmamalı. Backend/veritabanı değişmedi.

## M-T3 — onay öncesi kısa tasarım arası

Kullanıcının ekranında `M-T3 — Profilimde göster` (8 Eylül, 20:00–22:00)
daveti bekliyor, profilde gösterim kutusu seçili değil. Henüz onay sonucu
doğrulanmadı. Katılım kartında kutunun altındaki tekrar açıklaması kaldırıldı.
Mevcut detay penceresi artık seçeneğin sağındaki bilgi ikonundan açılıyor.
Bilgiye dokunmak kutuyu değiştirmez. Profil gösterim davetinin (bağlı profil)
ayrı açıklaması korunur. Backend veya veritabanı değişmedi.

Hot Restart sonrası aynı M-T3 davetinde “Bu etkinliği profilimde de göster”
seçilip onaylanacak. Yeni etkinlik oluşturmaya gerek yok.

## Son manuel doğrulama ve sekmeli yönetim revizyonu

Kullanıcı geçiş sonrası M-T1/M-T2'nin kişisel profilde görünmediğini ve yönetim
listesinde ikisinin de gizli olarak bulunduğunu doğruladı. Bu kontrol GEÇTİ.
Yeni davette doğrudan yayınlama (M-T3) henüz başlamadı.

Kullanıcı isteğiyle müzisyen/grup panelindeki giriş artık “Etkinlik Yönetimi”.
Açılan sayfa “Etkinlik Davetleri” ve “Güncel Etkinliklerim” sekmelerinden oluşur.
İkinci sekme önceki “Profilimdeki etkinlikler” listesidir. Onay ve göster/gizle
kuralları değişmedi, ek backend veya veritabanı geçişi yok. Güncel görünüm için
Flutter Hot Restart yeterli. Sıradaki manuel senaryo yine M-T3.

Sekmeli revizyon: Flutter tüm proje 1.459/1.459 test başarılı, tüm proje analizi
temiz. Dar ekran/büyük yazı, iki profil türü, sekmeye dönüşte taze veri ve
bekleyen onay/yayın işlemi sırasında eski sekme callback'lerinin engellenmesi
regresyon testlerinde doğrulandı. Bu sonuçlar telefon testinin yerine geçmez.

## Test hesapları ve mevcut durum

- Mekân: `soundconnectankara`.
- Müzisyen: `bugrasahin`.
- Grup: aynı müzisyene ait `Şahbaz` (`sahbaz`).
- Mekân ve müzisyen birbirine bağlı DEĞİL (kullanıcı doğruladı).
- Mevcut veri korunacak; test için veritabanı sıfırlanmayacak.

## M-T1 — Katıl, gösterme

Etkinlik: `M-T1 — Katıl, gösterme`, 06.09.2026, 20:00–22:00.
Seçilen kişi müzisyen `bugrasahin`; grup değil. Afiş yüklenmedi.

Kullanıcının doğruladığı GEÇEN ilk aşama:

1. Müzisyen onayı olmadan mekân etkinliği oluşturuldu.
2. Varsayılan afiş görünüyor.
3. Henüz onay yokken müzisyen profili tıklanamıyor.

İkinci aşama GEÇTİ (kullanıcı doğruladı): profilde gösterim kutusu boşken
katılım onaylandı, mekan etkinliğinde @bugrasahin bağlantısı açıldı ve müzisyen
profilinde etkinlik görünmedi. Yeni etkinlik bazlı düzene geçişten sonra gizli
kalması ayrıca doğrulanacak.

## M-T2 — Katıl, göster

Kullanıcı yeni davette profilde gösterimi seçerek onayı başarıyla tamamladı.
Haftalık takvim ayarının KAPALI olduğunu açıkça belirtti. Bu noktada eski
akışın kafa karıştırdığı görüldü ve test duraklatıldı. M-T2'nin takvim kapalı
ve açıkken profil görünürlüğü kontrolleri henüz GEÇTİ sayılmıyor.

Son ürün kararı: genel takvim ayarı tamamen kaldırıldı. Katılımı onaylanan
etkinliğin gösterimi yalnızca etkinlik bazında seçilir. Sonradan
“Profilimdeki etkinlikler” bölümünden göster/gizle yapılabilir. Önceki
ayarlara yönlendirme revizyonu artık kullanılmıyor.

Kullanıcı backend'i kapattı ve veri geçişini onayladı. 6 Eylül 2026 01:31 TRT'de
`scripts/db/2026-09-06-event-profile-publications.sql` gerçek yerel veritabanına
uygulandı. Önce yedek ayrı veritabanına geri yüklendi, geçiş iki kez prova edildi.
Asıl geçişte 84 tablonun beklenen veri parmak izi eşleşti. İki etkinlik ve iki
katılım onayı korundu. M-T1 ve M-T2 gizli, ilk kabul tercihleri sırasıyla false ve
true olarak ayrı karar kaydında korunuyor. Veritabanı sıfırlanmadı.
Yedek: `../../.local-backups/event-profile-publications-20260906/before.dump`.

Frontend tüm proje 1.453/1.453 test başarılı, tüm proje analizi temiz.
Gerçek yönetim/davet ekranları iki ek görsel testle render edildi. Backend geniş
222 test ve son 90 test koşusu başarılı, üç ek senaryo ile toplam 225 farklı
ilgili test doğrulandı. Gerçek PostgreSQL ve Redis kontrolleri dahil.
Yeni düzen telefonda henüz test edilmedi. Aşağıdaki eski tasarım kayıtları
tarihsel notlardır.

## Devam sırası

Her maddeye kullanıcı ile tek tek geçilecek; aşağıdakiler tamamlandı sayılmaz:

1. Yedek ve migration TAMAMLANDI. Kullanıcı güncel backend'i başlatıp Flutter
   Hot Restart yapacak. Ayarlarda artık Haftalık Takvim seçeneği olmamalı.
   M-T1 ve M-T2 gizli kalmalı, mekan profil bağlantıları korunmalı. Bu ekran
   kontrolleri yeni sürümde henüz kullanıcı tarafından doğrulanmadı.
2. Yeni `M-T3 — Profilimde göster` davetini oluştur. Profilimde de göster
   seçili onay doğrudan yayınlamalı, başka ayar istememeli. Etkinliği doğru
   haftaya koy. Yönetim Paneli → Etkinlik Davetleri → Profilimdeki etkinlikler
   (yeni yol: Etkinlik Yönetimi → Güncel Etkinliklerim) bölümünden gizle, yeniden
   göster. Mekandaki link ve katılım aynı kalmalı.
3. Bağlantısız müzisyen davetini reddet: mekândaki etkinlik korunur, kişi
   bağlantısı açılmaz, müzisyen profilinde görünmez.
4. Bağlı müzisyen: bağlantı baştan kullanılabilir; profil yayını için ayrıca
   izin istenir. Yayın reddi mekândaki profil bağlantısını kapatmaz.
5. Grup kurucusu grup profilinde yayınlasın. Üyelerin kişisel profillerinde
   otomatik görünmemeli. Üye kendi listesinden göstersin, grup profilindeki
   gizleme bu kişisel seçimi değiştirmemeli. Üye ayrılıp yeniden katıldığında
   eski kişisel yayını yeniden seçim yapmadan canlanmamalı.
6. Davet yetkisi, tekrar karar/gönderim korumaları, bildirim hedefi, etkinlik
   silme/geçmiş ve Aktif Mekânlar'ın değişmemesi.

Takvim penceresi bugünden başlayarak yedi gündür. Uzun testlerde etkinlik
tarihinin hâlâ pencere içinde olduğuna dikkat et. İlk katılım kararını tekrar
göndererek değiştirmeye çalışma. Sonraki yayın tercihleri yeni göster/gizle
bölümünden yönetilir. Genel takvim anahtarı artık yoktur.

## Tasarım arası — tamamlanan teknik kontroller

Detay görünümü yenilendi, uydurma MANUAL açıklamaları kaldırıldı, gerçek logo
amblemli ortak afiş uygulandı. 113 ilgili otomatik test ve gerçek widget görsel
önizleme testi geçti; proje geneli Flutter analizi temiz. Bu otomatik sonuçlar
manuel senaryoların yerine geçmez. Backend/veritabanı değiştirilmedi.

Görünüm yedeği ve yalnızca tasarımı geri alan yama:
`../../.local-backups/event-detail-design-before-20260905/`.
Kullanıcı yeni detay düzenini beğenmedi ve önceki görünümü istedi. Önceki
detay tasarımı geri getirildi; gerçek amblemli afiş, açıklama düzeltmeleri,
saat biçimi ve güvenli profil bağlantıları korundu. Kullanıcının ek isteği
bekleniyor; M-T1 ikinci aşama hâlâ yapılmadı.

Önceki tasarıma dönüş ayrıca doğrulandı: 113 ilgili regresyon testi ve bir
gerçek-widget görsel testi (toplam 114) geçti. Yeni amblemli varsayılan afiş
değiştirilmedi. Manuel testler hâlâ aynı noktada bekliyor.

## Katılım bilgisi ikonu — test arası ekleme

Detay ekranında profil bağlantısı bulunmayan, adı belli sanatçı/grup düz isim
ve bilgi ikonu ile gösteriliyor; ismin kendisi tıklanamıyor. İkon, isim adına
katılımın doğrulanmadığını açıklıyor. Public veri onaysız kaydın müzisyen/grup
türünü ve davetin bekleme/ret ayrımını gizlediğinden metin tür veya davet
durumu tahmin etmiyor. Henüz açıklanmamış/boş isimlere uyarı konmuyor.

Geçerli public profil bağlantısı varsa `@isim` ve profil yönlendirmesi korunur;
bilgi ikonu gösterilmez. Bu görünüm takvimde gösterme iznine veya genel takvim
anahtarına bağlı değildir. Backend/veritabanı değişmedi.

128 ilgili otomatik/görsel test geçti; değişen kaynak/test dosyalarının analizi
temiz. M-T1 hâlâ katılım onayı verilmeden bekliyor; kullanıcı ikinci isteğini
iletecek, ondan sonra manuel testlere devam edilecek.

## Kimlik kutuları ve Paylaş — test arası düzeltme

Sanatçı/grup ile mekân kutuları artık ayrılmış tek bir satırda, eşit yükseklikte
kalır. Uzun adlar tek satırda kısalır; tam metin tooltip ile erişilebilir.
Tarih/saat/konum bunların altındadır. Paylaş, mekân/müzisyen profilindeki
Yönetim Paneli butonunun 0.7 gradient kenarlık ve 18 köşe tasarımını kullanır;
paylaşım sürerken tekrarlı işlem ve yükseklik değişimi engellenir.

154 ilgili test geçti (24 farklı kimlik yerleşimi ve gerçek-widget önizleme
dahil); değişen dosyaların analizi temiz. Backend/veritabanı değişmedi.
Kullanıcı son bir isteğini daha iletecek. M-T1 katılım onayı hâlâ VERİLMEDİ;
manuel test sırası değiştirilmedi.

## Görsel etkinlik paylaşımı — test arası son istek

Katılım bilgi kutusu tek cümleye indirildi: “Sanatçı/grup bu etkinliğe
katılımını henüz doğrulamadı.” İsim tekrar edilmiyor.

Paylaş butonu, güncel public etkinlik detayından 1080×1920 PNG üretir. Önce
görsel önizlemesi, sonra Android'de Instagram Hikâyesi / WhatsApp / Diğer
seçenekleri açılır. Önizlenen PNG aynen paylaşılır. Afiş yoksa gerçek amblemli
yerel tasarım, varsa afişin tamamı ve renklerinden bulanık arka plan kullanılır.
Onaysız sanatçı/grup düz isim ve katılımın doğrulanmadığı notuyla gösterilir;
geçerli public profil bağlantısı varsa @ korunur. Takvim izinleri değiştirilmez.

İptal dış uygulama açmaz; çift dokunma tek işlem üretir. Güncel detay
alınamazsa veya etkinlik artık yoksa eski ekran verisinden paylaşım yapılmaz.
Görsel indirme/çözümleme başarısızsa yerel afiş kullanılır. Mevcut Collab Android
kanalı yeniden kullanılır; native kod, backend uygulama kodu, veritabanı ve
bağımlılıklar değiştirilmedi. Uygulama bulunamazsa PNG ile sistem paylaşımı açılır.

211 ilgili otomatik/görsel test geçti; değişen kaynak/test dosyalarının statik
analizi temiz. Gerçek üretilmiş PNG'ler (afişsiz, onaylı, uzun başlık, afişli)
ve paylaşım paneli görsel olarak incelendi. Instagram/WhatsApp'ın telefonda
gerçek açılması henüz manuel doğrulanmadı; otomatik platform testleri mock
kanal/sistem paylaşımı kullanır.

M-T1 birinci aşama kullanıcı tarafından GEÇTİ; ikinci aşama hâlâ yapılmadı.
Sonraki manuel adım: bugrasahin hesabında daveti, profilde gösterme tercihi
KAPALI iken kabul etmek; mekândaki profil bağlantısı açılırken müzisyen
takviminde etkinliğin görünmediğini doğrulamak. Bu adımdan önce kullanıcı
paylaşım tasarımını/telefondaki paylaşımı inceleyecek. Etkinlik silinmedi,
davet onaylanmadı, backend başlatılmadı/durdurulmadı.

### Paylaşım tasarımına son düzeltme

Kullanıcı açıkça seçti: katılımın doğrulanmadığı not paylaşım görselinden ve
paylaşım metninden tamamen kaldırıldı; onaysız sanatçı/grup düz isim, onaylı
profil @isim olarak kalır. Detay sayfasındaki katılım bilgi ikonu/metni ve
onay-yayın kuralları değişmedi.

Başlığın altında, mekânın etkinliği oluştururken yazdığı gerçek etkinlik
açıklaması varsa gösterilir. Kaynak, paylaşım öncesi alınan güncel public
etkinlik detayının description alanıdır; mekân biyografisi veya eski ekran
metniyle doldurulmaz. Uzun açıklama kalan alana göre en fazla dört satırlık
özetle gösterilir; metin destekleyen paylaşım hedeflerine tam açıklama gider.
Açıklama yoksa sahte metin/başlık üretilmez. Afiş ve diğer tasarım korunur.

133 ilgili otomatik/görsel test geçti. Açıklamalı kısa/uzun başlık, afişli ve
açıklamasız PNG örnekleri üretildi; açıklamalı örnekler görsel olarak incelendi.
M-T1 manuel testinde hâlâ katılım onayı verilmedi; sıradaki adım değişmedi.

### WhatsApp paylaşım metni — mağaza yönlendirmesi

Kullanıcı WhatsApp paylaşımında otomatik etkinlik dökümünü gördü ve kaldırılmasını
istedi. Artık dış uygulamaya yalnızca “Etkinlik detayları için:” ve gönderen
cihaza uygun mağaza URL yer tutucusu gider: Android için Google Play, iOS için
App Store. Diğer platformlarda nötr uygulama indirme yer tutucusu kullanılır.
Gerçek mağaza URL'leri uygulama yayına çıkmadan önce
`event_share_message.dart` içindeki TODO(release) noktasına eklenecek.

PNG tasarımı ve görseldeki etkinlik açıklaması değişmedi. Ayrıntılı etkinlik
metni yalnızca önizlemenin ekran okuyucu açıklamasında kaldı; WhatsApp ve
sistem paylaşımına gönderilmez. Android doğrudan paylaşım ve uygulama/plugin
bulunamadığında sistem paylaşımı aynı kısa metni kullanır. 141 ilgili test
geçti. M-T1 katıl/profilde gösterme manuel adımı hâlâ bekliyor.

### Davet kartı açıklamaları ve yerleşimi — test arası revizyon

Katılım daveti artık onay verilirse mekân etkinliğinde profil bağlantısının
açılacağını ve bunun SoundConnect görünürlüğüne katkısını anlatıyor. Grup
davetlerinde metin grup profilini açıkça belirtiyor. Bağlı taraflara gelen
yalnızca gösterim davetlerinde mevcut profil bağlantısının zaten açık olduğu
kuralı korunuyor.

Kutucuk ile metin arasındaki fazla boşluk azaltıldı; izin satırları ortak
hizaya alındı ve kartın dikey aralıkları düzenlendi. Dokunma alanı en az 48
mantıksal piksel olarak korundu. Kısa açıklamadaki virgül ve “takvim anahtarı”
ifadesi kaldırıldı. “Detaylar için dokun” salt okunur bir açıklama penceresi
açar: etkinlik bazında gösterim izni, Ayarlar > Etkinlik Ayarları > Haftalık
Takvim tercihi ve gruplarda grup/üye görünürlük koşulları açıklanır. Pencereyi
açmak veya kapatmak hiçbir tercihi ya da davet kararını değiştirmez.

115 ilgili otomatik/görsel test geçti. Küçük ekran, büyütülmüş yazı,
müzisyen/grup, katılım/gösterim daveti, tekrarlı dokunma ve ekranın kapanması
senaryoları kontrol edildi; gerçek widget önizlemeleri görsel olarak incelendi.
Değişen sekiz kaynak/test dosyasının statik analizi temiz. Backend uygulama
kodu, veritabanı ve onay/yayın kuralları değiştirilmedi.

M-T1 ikinci aşaması hâlâ YAPILMADI: bugrasahin hesabında “Bu etkinliği
profilimde de göster” KAPALI bırakılarak davet onaylanacak. Önce kullanıcının
bu tasarımı telefonda kontrol etmesi bekleniyor. Onay sonucu alındıktan sonra
mekândaki profil bağlantısı ve müzisyen takviminde görünmeme kontrol edilecek.
