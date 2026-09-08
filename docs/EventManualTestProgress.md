# Etkinlik akışı — manuel test kaydı

Son güncelleme: 7 Eylül 2026. Ana akış: `ReciprocalEventFlow.md`.

## Güncel — son iki manuel kontrol ertelendi, misafir keşif tasarımı

Kullanıcı `QA - Tarih ve silme` etkinliğini kendisi oluşturduğunu ve Sahbaz
tarafındaki daveti onayladığını bildirdi. Ardından manuel tekrarları sonlandırmayı
tercih etti. Gerçek bitiş sonrası Geçmiş'e geçiş ve silme sonrası tüm ilgili
profillerden temizlenme gözlemlenmedi. Bu iki başlık GEÇTİ değil, ERTELENDİ.
Kişisel gösterimin bu yeni QA kaydı için ayrıca hazırlandığı doğrulanmadı.

Kullanıcı daha sonra misafir girişindeki etkinlik keşfini bugün dahil yedi gün,
Bugün / tarihli Yarın / sınırlı tarih seçimi düzenine çevirmeyi istedi. Tasarım
denemesi eski ekrana tek ayarla dönülebilecek şekilde izole edildi. Bu çalışma
mevcut etkinlik davet, bağlantı veya profil gösterim izinlerini değiştirmez.
Otomatik test verileri canlı hesaplara/etkinliklere eklenmez.

## Güncel — B-T2 geçti, bilgisayar üzerinden kalan kontroller

Kullanıcı B-T2 bağlı grup akışını doğruladı: davet kararı öncesinde grup
bağlantısı açık, gösterim daveti reddedildiğinde mekan etkinliği ve grup
bağlantısı korunuyor. Sonradan grup gösterimi açılabiliyor. Kişisel gösterimi
kapatmak grubu etkilemiyor, grup gösterimini kapatmak kişisel gösterimi de
sınırlıyor, grup gösterimi yeniden açılınca açık kişisel tercih uygulanıyor.
Bu manuel başlık GEÇTİ. Eski davet/tekrarlı işlem senaryolarının telefonda
tekrarı kullanıcı tercihiyle atlandı, otomatik test kapsamına dayanılıyor.

Asistan 7 Eylül 2026 yaklaşık 18:17'de scrcpy/V2206 üzerinden bugrasahin
hesabının Etkinliklerim → Geçmiş ekranını açtı. Sekme açılıyor, mevcut liste
boş. Bu yalnız boş durum kontrolüdür, zaman geçişi testi yapılmış sayılmaz.
Hiçbir etkinlik oluşturulmadı, silinmedi veya gösterim tercihi değiştirilmedi.
Gözlem anında B-T2 kişisel listede gizliydi. Önceki kullanıcı doğrulamalarını
geri almıyoruz, tercih daha sonra değişmiş olabilir.

Kalan işler: ayrı bir kısa süreli test etkinliğinin geçmişe geçmesi ve
onaylı kişisel/grup geçmişlerinin kontrolü, ardından yalnız bu test kaydının
silinmesiyle temizlenme ve mekan bağlantılarının korunması. Hesap girişini
kullanıcı yapacak. Silme, Windows UI üzerinden yapılmadan hemen önce ayrıca
onay istenecek. Hesap şifreleri bu kayda alınmadı. Alttaki B-T2 başlamadı
notları artık tarihsel kayıttır.

## Güncel — bağlı müzisyen testi geçti, B-T2 başlamadı

Kullanıcı M-T5 — Bağlı müzisyen etkinliğini soundconnectankara hesabından
bugrasahin seçerek oluşturdu. Davete yanıt verilmeden @bugrasahin açıldı.
Etkinlik müzisyen profilinde otomatik görünmedi. Gösterim daveti reddedildiğinde
mekan etkinliği ve profil bağlantısı korundu. Etkinliklerim'den sonradan
Profilimde göster açıldığında etkinlik müzisyen takviminde göründü. GEÇTİ.

Şahbaz ve soundconnectankara Bağlantılarım üzerinden bağlı. Sıradaki adım
mekandan B-T2 — Bağlı grup oluşturmak, ancak kullanıcı henüz oluşturmadı.
Önce Aktif Sanatçılar → Tümü ekranı eklenecek ve telefonda kontrol edilecek.
Bu tasarım arası, sonraki grup/davet/tarih/silme kontrollerini tamamlanmış saymaz.

## Güncel — müzisyen ve mekan bağlantısı doğrulandı

bugrasahin isteği gönderdi. soundconnectankara hesabında Gelen İstekler'de
göründü, kabul edildi ve Bağlantılarım'a geçti. Kullanıcı ayrıca müzisyen
tarafındaki Bağlantılarım listesini, Çaldığı Mekanlar kartını ve mekanın
Aktif Sanatçılar alanındaki bugrasahin kartını doğruladı. Bu ön koşul GEÇTİ.

Testler, mekan/müzisyen/grup profillerindeki Türkçe metin düzeltmeleri için
duraklatıldı. Sıradaki test bağlı müzisyenin etkinliğe eklenmesi ve profil
gösterim davetidir. Henüz bu yeni etkinlik oluşturulmadı. Alttaki eski
“bağlı değiller / istek gönder” yönlendirmeleri artık geçerli değil.

Türkçe düzeltmeleri: profil/yönetim/bağlantı ve ortak medya ekranlarının
görünen metinleri düzeltildi. Teknik kimlikler, arama normalizasyonu ve eski
davet metnini ayrıştıran uyumluluk kodu korunuyor. `dart analyze lib test`
temiz. Tam Flutter koşusunda 2394 test geçti, bir test eski yükleme metnini
beklediği için takıldı. Bu beklenti düzeltildikten sonra ilgili profil ve
bağlantı testlerinin 53'ü de geçti. İş akışı veya veritabanı değişikliği yok.

## Güncel — bağlantı yönetimi menüsü ve sıradaki test

Kullanıcı mekan B-T1'in korunduğunu ve @Şahbaz bağlantısının çalıştığını
doğruladı. Aşağıdaki altı ana kontrolün ilki geçti, beş başlık kaldı.
bugrasahin ile soundconnectankara henüz bağlı değil. Bağlantı oluşturma
adımından önce menü düzeni revize edildi.

Müzisyen/grupta **Mekan Bağlantıları**, mekanda **Sanatçı Bağlantıları**
ortak yönetim menüsünü açar. Seçenekler **Bağlantılarım**, **Gelen İstekler**,
**Gönderdiğim İstekler**. Ayrı gradient düğme **Mekan ekle / Sanatçı ekle**.
Bağlantılarım iki yönden gelen ACCEPTED kayıtlarını sunucuda filtreleyip
20'şer getirir. İstek listelerinin mevcut durum geçmişi korunur. Backend
kontratı, veritabanı ve etkinlik onay/gösterim kuralları değişmedi.

Doğrulama: ilgili dört Flutter dosyasında 91 test geçti, `dart analyze lib test`
temiz. Tam süitte 2389 test geçti, yeni iki sayfalama testindeki yön beklentisi
yanlıştı (connectionsOnly yönü yok sayar). Yalnız test beklentileri düzeltildi,
ardından bu ikisini de içeren ilgili 91 testin tamamı yeniden geçti.

Sıradaki manuel adım: bugrasahin → Yönetim Paneli → Mekan Bağlantıları →
Mekan ekle üzerinden soundconnectankara'ya istek gönder. Henüz bu isteğin
gönderildiği veya kabul edildiği doğrulanmadı. Ardından mekan hesabında
Sanatçı Bağlantıları → Gelen İstekler ile kabul edip iki tarafın
Bağlantılarım listesini kontrol edeceğiz. Bu bölüm önceki sıradaki-adım
notlarının yerine geçer.

## Güncel — kurucunun üye çıkarma adımı geçti

Kullanıcı bugrasahin hesabında Şahbaz → Üyeleri Yönet üzerinden aedrum'u
çıkardı. Başarı sonucu, aedrum'un aktif üyelerden kalkması, sayının 1 olması
ve kurucu kartı/unvanının korunması kullanıcı tarafından doğrulandı.
**Üye çıkarma ve aedrum tarafındaki yetki kaybı GEÇTİ.** Kullanıcı aedrum
hesabında Şahbaz'ın Bandlerim'den, kişisel B-T1'in profilinden kalktığını
doğruladı. Aramadan Şahbaz açıldığında ziyaretçi profili geliyor. Yönetim,
düzenleme ve Gruptan ayrıl yok. Üyelerde yalnız bugrasahin var.

Etkinlik manuel akışını kapatmak için kalan kontroller altı ana başlıkta
toplandı (tek tek dokunma/adım sayısı değildir, yeni hata çıkarsa ek kontrol
gerekebilir):

1. Üye çıkarma sonrası mekan B-T1 ve onaylı grup profil bağlantısı korunuyor mu?
2. Bağlı müzisyen için katılım ile profil yayınının ayrımı.
3. Bağlı grup için aynı ayrım ve kişisel/grup gösterim bağımsızlığı.
4. Yeni davet kimliği düzeninde eski davet/bildirim ve tekrar karar kontrolü.
5. Geçmiş etkinlik listeleri ve tarih sınırları. Önceden geçen ileri tarih
   bilgilendirmesi tekrar baştan alınmayacak.
6. Etkinlik silinince ilgili listeler/gösterimler temizlenirken mekan
   bağlantılarının korunması.

Sıradaki tek adım 1: aedrum hesabından soundconnectankara profilindeki B-T1'i
açıp etkinliğin durduğunu ve @Şahbaz bağlantısının grup profilini açtığını
kontrol etmek. Etkinlik veya bağlantı henüz silinmeyecek.

Kısa isimleri ortalama denemesi kullanıcı tarafından reddedildi ve tamamen
geri alındı. Önceki 168 × 52 temel ölçülü, soldan hizalı ortak mini kart
korunuyor. Grup üyeleri, grup/müzisyen mekanları ve mekanın aktif sanatçıları
aynı önceki tasarımda. Denemeye özgü test beklentileri de geri alındı.
Geri alma sonrası mevcut kart testleri 13/13 geçti, kartın Dart analizi temiz.
Diğer düzeltmeler, üyelik/bağlantı/etkinlik kuralları ve test ilerlemesi
korundu. Güncel ilerleme ve sıradaki adım yukarıda kayıtlıdır.

## Güncel — yedekli bağlı modül geçişi tamamlandı

7 Eylül 2026 15:18 +03:00: kullanıcı backend'i durdurdu. Tam yedek alınıp
geçici PostgreSQL'de geri yüklendi, üç geçiş önce bu kopyada prova edildi ve
sonra yerel DB'ye uygulandı. 84 iş tablosunun veri özetleri değişmedi.
Davet kimliği, beş bağlantı indeksi ve 25 teknik bildirim kaydı doğrulandı.
Backend asistan tarafından açılmadı. Kullanıcı güncel backend ve Flutter'ı
yeniden çalıştırınca **kurucunun üyeyi çıkarması / yetki kaybı** adımına
dönülecek. B-T1 ve aedrum üyeliği/gösterim tercihleri korunuyor.
Yedek ve kontroller: `ConnectedModulesLocalMigration20260907.md`.

## Son ara — ikinci bağlı modül denetimi

Kullanıcı manuel testte kaçabilecek koşullar için bildirim/müzisyen/grup/mekan
bağlantısı akışını yeniden ayrıntılı denetlemeyi istedi. Yeni denetim ve
doğrulama `ConnectedModulesScenarioMatrix.md` içinde. Eski üyelik ekranının
yeniden katılınmış üyeliği çıkarma/unvan/ayrılma ile değiştirememesi, yeni
bağlantının iki tarafında aktif/doğrulanmış hesap şartı ve silinmiş bildirimin
gecikmiş tekrarının engellenmesi kullanıcı tarafından onaylandı.

İkinci tur düzeltmeleri uygulandı. Backend son izole koşu **902/902**, 0
atlama, tam Flutter **2372/2372**. Son kaynakta `dart analyze lib test` temiz.
Ayrıntılı kapsam ve kanıtlar senaryo raporunda kayıtlıdır. Bunlar gerçek telefonda
yeni sürümün çalıştığı veya canlı veri geçişinin yapıldığı anlamına gelmez.

Kod denetimi turunda gerçek hesap, davet, B-T1, üyelik veya bağlantı
değiştirilmedi. O sırada bekleyen geçişler sonraki kullanıcı teyidiyle
yukarıdaki yedekli adımda tamamlandı.
Manuel durak değişmedi: aedrum yeniden katılmış durumda, kişisel B-T1'i
göstermişti. Grup B-T1'i gizli tutuyor, mekan etkinliği ve @Şahbaz bağlantısı
doğruydu. Sonraki manuel adım kurucunun üye çıkarması ve yetki kaybı kontrolü.

## Güncel durak — yeni üyelikte kişisel gösterim geçti, tasarım ve çapraz modül denetimi

Son teyit: yeni üyelikte aedrum B-T1'i kendi profilinde yeniden gösterdi.
Kullanıcı ayrıca Şahbaz grup profilinde B-T1'in hâlâ gizli, mekan profilinde
görünür ve @Şahbaz bağlantısının çalışır olduğunu doğruladı. Yeni üyelikte
kişisel gösterim ve diğer profillerden bağımsızlığı GEÇTİ.

Son tasarım kararı: kullanıcı 52 yüksekliğinde, 168 genişliğindeki kompakt
yatay üye kartlarını onayladı. Aynı görünüm şimdi grup/müzisyen profillerindeki
mekanlara ve mekan profilindeki aktif sanatçılara uygulanıyor. Bildirim,
müzisyen, grup ve sanatçı–mekan bağlantısı modüllerinin çapraz denetimi sürüyor.
Denetim sırasında gerçek üyelik, davet veya B-T1 verileri değiştirilmedi.
Kullanıcı iki ürün kararını onayladı: eski grup daveti yeni davete karar
veremez, grup oluşturma kotasında yalnız aktif kurucu olunan gruplar sayılır.
Yeni invitationId geçişi ve sayfalama indeksleri henüz gerçek veritabanına
uygulanmadı. Veri geçişi öncesi kullanıcı backend'i durdurmalı ve yedek alınmalı.
Veritabanı sıfırlanmayacak. Ayrıntılı durum: `ConnectedProfileModulesAudit.md`.
Denetim doğrulaması tamamlandı: tam Flutter **2.306/2.306**, backend
**402/402** (atlanan yok), son odaklı Flutter **34/34**, statik analiz temiz.
Telefon testine dönüş için yalnız yedekli geçiş ve yeni backend/Flutter
sürümlerinin birlikte açılması bekleniyor. B-T1 ve aedrum üyeliği korunacak.

Önceki görsel deneme (geri alındı): grup profilindeki üye önizleme kartları isteğe bağlı rolü olmayan
üyelerde de dengeli görünmesi için dikey avatar/isim/caption düzenine alındı.
Rol zorunlu yapılmadı, üyelik/etkinlik yetkileri değişmedi. Önceki üç kaynak
dosya `.local-verification/band-member-card-before/` içinde karşılaştırma için
saklandı. 72 odaklı Flutter testi geçti, değişen kaynakların analizi temiz.
Bu tasarım artık kullanılmıyor.

Kullanıcı aedrum hesabında Gelen Davetler üzerinden Şahbaz davetini kabul etti
ve sonuçları doğruladı: Şahbaz gruplarına geri geldi, gelen davet listesinden
kalktı, B-T1 kişisel profilde kendiliğinden görünmedi. Eski üyeliğin kişisel
gösterim tercihi yeni üyelikte geri gelmedi. Bu manuel adım GEÇTİ.

Bu kontrol tamamlandı: aedrum yeni üyelikte B-T1'i kendi profilinde gösterdi.
Şahbaz'ın ayrı gösterim tercihi değişmedi. Yeni manuel adıma henüz geçilmedi.
Denetim sonrasında aynı verilerle üye çıkarma ve yetki kaybı kontrollerinden
devam edilebilir. Aşağıdaki eski duraklar tarihçedir, güncel bekleyen adım değildir.

## Önceki durak — yeniden davet testinde bekleyen liste arası

Yeni ara: kullanıcı davet ekranındaki metinleri onayladı, **aedrum hâlâ kabul
etmedi**. Müzisyen Yönetim Paneli → Bandlerim ekranına **Gelen Davetler** bölümü
eklendi. Backend yeniden başlatılıp Flutter Hot Restart sonrası mevcut Şahbaz
daveti bu bölümden açılarak kabul edilecek. Eski daveti yeniden göndermek veya
B-T1'i yeniden oluşturmak gerekmiyor. Ayrıntılar `BandReceivedInvitations.md`.
Doğrulama tamam: backend **119/119**, tam Flutter **2.216/2.216**, statik analiz
temiz. Altı dar ekran/tema önizlemesi üretildi. Telefonda önce yeni Gelen
Davetler bölümündeki Şahbaz kartının görünümü kontrol edilecek. Kullanıcı
teyidinden sonra aynı karttan kabul testine geçilecek, henüz kabul edilmedi.

Son telefon teyidi: bekleyen aedrum fotoğrafı düzeldi. Kullanıcı daveti henüz
kabul etmedi. Kabul ekranında metin arası verildi: “Grup Daveti”, “Sahbaz seni
gruba davet etti”, “bugrasahin tarafından davet aldın.” Eski bildirim metni
ekranda uyumlu gösterilir, yeni davetlerin backend şablonu da güncellendi.
Üyelik veya kabul/ret akışı değiştirilmedi. Sıradaki test hâlâ daveti kabul
edince üyeliğin geri gelmesi ama eski kişisel B-T1 gösteriminin geri gelmemesidir.

7 Eylül'de kullanıcı şu telefon kontrollerinin tamamını doğruladı:

- aedrum → Gruptan ayrıl → İptal üyeliği ve kişisel B-T1'i korudu.
- Gerçek ayrılma onaylandı. Şahbaz aedrum'un Gruplarım listesinden kalktı.
  B-T1 aedrum'un kişisel profilindeki takvimden kalktı.
- Mekandaki B-T1 korundu. Onaylı `@Şahbaz` bağlantısı dokunulabilir kaldı
  ve grup profilini açtı. Bireysel ayrılma grubun katılımını bozmadı.

Sıradaki test eski kişisel yayın tercihinin yeni üyelikte geri gelmemesidir.
**aedrum'un yeniden daveti gönderildi, durumu PENDING.** Kullanıcı bekleyen
listeyi telefonda doğruladı, salt okunur yerel veritabanı kontrolü de PENDING
durumunu doğruladı. Kabul henüz yapılmadı. aedrum kabul edince üyelik geri
gelmeli ama B-T1 kişisel profilde otomatik görünmemeli.

Bu adımda kullanıcı Üyeleri Yönet ekranında gönderilen, bekleyen davetleri
görmek istedi. Bekleyen Davetler bölümü eklendi. Yeni ekran kontrolü için
aedrum'un kabulünü şimdilik bekletmek uygun. Asıl test etkinliği silinmeyecek,
üyelik/davet/etkinlik verisi geliştirme sırasında değiştirilmeyecek.

Kullanıcı bekleyen liste tasarımını onayladı ancak aedrum'un fotoğrafı eksikti.
Salt okunur teşhis: eski User.profilePicture boş, müzisyen profilinin medya
kimliği dolu. Davetin eski olmasıyla ilgisi yok. Backend fotoğrafı artık
güncel müzisyen profilinden sayfa başına tek medya sorgusuyla çözümler.
**Veritabanı geçişi veya daveti tekrar gönderme gerekmez.** Bu düzeltme yalnız
backend kaynaklarını değiştirir. Doğrulama tamamlandı, kullanıcı backend'i
yeniden başlatıp Üyeleri Yönet sayfasını yenileyecek. Önce bekleyen aedrum
fotoğrafı kontrol edilecek, sonra kabul ve eski kişisel B-T1 tercihinin geri
gelmemesi testine dönülecek. Ayrıntılar `BandPendingInvitationsReview.md`.
Fotoğraf düzeltmesi: backend **197 geçti, 0 hata** (76 bekleyen davet testi dahil,
dört eski PostgreSQL başlık geçişi testi atlandı), mevcut Flutter bekleyen
davet testleri **67/67** geçti. Fotoğrafın telefondaki kontrolü kullanıcıyı bekliyor.
Fotoğraf düzeltmesinden önceki doğrulama: tam Flutter paketi **2.179/2.179**, odaklı paket **128/128**, yeni
endpoint ve önceki backend regresyonları **174 geçti**. Dört eski Docker'a
bağlı geçiş testi ortam nedeniyle atlandı. Altı gerçek-font görsel önizleme
geçti. Bekleyen davet listesi telefonda onaylandı, fotoğraf düzeltmesinin telefon kontrolü henüz yapılmadı.
Son statik analiz temiz.

## Önceki durak — grup başlıkları geçişi tamam, telefon kontrolü

7 Eylül görsel ara: kullanıcı başlıkların çalışıyor göründüğünü söyledi ve
aedrum'un **Davul** başlığıyla göründüğü ekranı paylaştı. Tüm başlık sınırı,
temizleme ve yetki senaryoları henüz telefonda geçti sayılmıyor. Dağınık
profil oku/kalem/çıkarma ikonları yerine tek üç nokta menüsüne geçildi.
Rol düzenleme ve çıkarma bu menüde, profil bağlantısı kartın isim/avatar
alanında kaldı. Bu değişiklik backend veya veri geçişi gerektirmez.
Menü/rol/çıkarma için 61 odaklı Flutter testi geçti. Normal ve büyük yazılı
kart, menü ve editör önizlemeleri kontrol edildi. Etkinlik testinin durduğu
yer değişmedi, mevcut üyelik/yayın verisine dokunulmadı.
Bu sadeleştirmeden sonraki tam Flutter paketi **2.112/2.112** geçti, statik
analiz temiz. Sıradaki görsel kontrol için yalnız Hot Restart yeterli.

Kullanıcının isteğiyle kurucu ve aktif üyeler için gruba özel, serbest yazılan
**20 karakterlik rol/başlık** eklendi. Kurucu rozeti yönetim yetkisinden bağımsız
başlıkla birlikte korunur. Kurucu kendisinin ve üyelerin başlığını kalemden
düzenler. Başlık üyelik veya etkinlik gösterim tercihini değiştirmez.

Yeni frontend tam paketi **2.099/2.099** ve altı responsive önizleme testi geçti.
Son statik analiz temiz.
Backend odaklı paket **121 geçti**, dört PostgreSQL geçiş testi Docker olmadığı
için atlandı. Doğrulama ayrıntıları ve derleme ortamı sınırı `BandMemberTitles.md`
içinde kayıtlıdır. Başlık özelliğinin telefon testi henüz yapılmadı.

**7 Eylül'de veri geçişi uygulandı.** Kullanıcı backend'i durdurduğunu teyit etti.
Tam yedek alındı, ayrı PostgreSQL veritabanına geri yüklenerek geçiş iki kez
denendi. Sonra yalnız `2026-09-06-band-member-titles.sql` yerel veritabanına
uygulandı. 84 tablonun eski alanlarının tamamı önce/sonra aynı kaldı. Veritabanı
sıfırlanmadı. Yedek ve doğrulama ayrıntıları `BandMemberTitles.md` başındadır.
Backend'i kullanıcı başlatacak, uygulamada Hot Restart yapılacak.
Aşağıdaki eski durakta yazan “backend yeniden başlatma/veri geçişi gerekmez”
ifadesi yalnız önceki profil yönlendirmesi düzeltmesine aittir, bu yeni özellik
için geçerli değildir.

Etkinlik testindeki durak değişmedi: **aedrum aktif Şahbaz üyesi, B-T1 kişisel
profilinde görünür, Şahbaz grup profilinde gizli. Gruptan ayrılma testi yapılmadı.**
Bu geliştirme sırasında hesap, üyelik, etkinlik veya yayın verisi değiştirilmedi.

Backend ve uygulama yenilemesi tamamlanınca ilk kısa kontrol: bugrasahin → Şahbaz
→ Yönetim Paneli → Üyeleri Yönet → ilgili üyenin üç noktası → Rolü düzenle.
Kurucuya ve aedrum'a başlık ekle, değiştir,
20 karakter sınırını ve boş bırakarak kaldırmayı kontrol et. Kurucu rozeti kalmalı,
aedrum başlık düzenleme yetkisi almamalı. Sonra aşağıdaki kendi profil bağlantısı
ve gruptan ayrılma testine dönülecek. B-T1 silinmeyecek veya yeniden kurulmayacak.

## Önceki durak — aedrum kişisel yayın testi geçti, ayrılma öncesi düzeltme

Kullanıcı aedrum'un Şahbaz üyeliğini ve şu kontrolleri telefonda doğruladı:

- B-T1 kişisel Etkinliklerim listesinde başlangıçta gizli ve tek kayıt olarak
  göründü. Kişisel profile kendiliğinden eklenmedi.
- aedrum kendi kişisel tercihinden **Profilimde göster** seçti. B-T1 kişisel
  profilinde bir kez göründü. Şahbaz grubunun gizli tercihi değişmedi ve
  bugrasahin'in kişisel profiline kendiliğinden eklenmedi.

**Mevcut veri korunacak:** aedrum ACTIVE Şahbaz üyesi, B-T1 aedrum kişisel
profilinde görünür ve Şahbaz grup profilinde gizli. Etkinlik silinmeyecek veya
yeniden oluşturulmayacak. Ayrılma testi henüz yapılmadı.

Bu noktada kullanıcı iki hata bildirdi: aedrum grup profilinin üye listesinden
kendisine dokunduğunda public profil açılıyor ve gruptan ayrılma seçeneği yok.
Ortak profil yönlendirmesi ve üye ayrılma arayüzü için teknik düzeltme tamamlandı.
Kod testi telefon testinin yerine geçmez. Canlı backend/veritabanı veya üyelik
bu düzeltme sırasında değiştirilmiyor.

Doğrulama: tüm Flutter paketi **1.957/1.957**, izole backend üyelik/yayın/takvim
paketi **35/35** GEÇTİ. Statik analiz temiz. İki gerçek Flutter önizleme testi
de geçti ve normal/büyük yazılı profil ile ayrılma penceresinin dört görseli
incelendi. Detaylar `ProfileNavigationAndBandMembershipReview.md` içindedir.
Backend yeniden başlatma, APK kurulumu veya veri geçişi gerekmez. Hot Restart
sonrasında aşağıdaki telefon kontrolüne devam edilecek.

Sıradaki telefon kontrolü: Hot Restart sonrası aedrum → Şahbaz → Üyeler →
aedrum ile kendi yönetilebilir müzisyen profilinin açıldığını doğrula. Sonra
Şahbaz'a dön ve **Gruptan ayrıl** eylemini kontrol et. İlk kontrolde yalnız
onay penceresi açılıp Vazgeç seçilebilir. Asıl ayrılma onayı ayrı test adımıdır.
Başarılı ayrılma sonrası kişisel B-T1 yayını ve üyelik kalkmalı. Mekandaki B-T1,
grubun katılım onayı ve diğer üyelerin tercihleri korunmalı.

## Önceki durak — aedrum aktif üye, kişisel etkinlik listesi kontrolü

Kullanıcı aedrum hesabının Şahbaz'a davet edildiğini ve üyelik davetini kabul
ettiğini doğruladı. aedrum artık aktif grup üyesi. B-T1 grup profilinde gizli
kalmaya devam ediyor, aedrum henüz kişisel etkinlik yayın tercihi yapmadı.

Sıradaki kontrol aedrum'un kendi müzisyen Yönetim Paneli → Etkinlik Yönetimi →
Etkinliklerim → Bu Haftaki bölümüdür. B-T1 bir kez gizli olarak listelenmeli
ve “Profilimde göster” seçeneği olmalı. Kişisel profil takvimine otomatik
eklenmemiş olmalı. Bu iki kontrol henüz GEÇTİ sayılmıyor. Grup yönetiminden
veya etkinlik davetlerinden işlem yapılmayacak, yeni etkinlik oluşturulmayacak.

## Önceki durak — ikinci grup üyesi hazırlanıyor, üye ekranında görsel ara

Kullanıcı grup kurucusunun etkinlik detayından kendi grup profiline yönlendirme
düzeltmesini doğruladı. B-T1'in gösterme/gizleme ve katılım bağlantısı kontrolleri
GEÇTİ. B-T1 halen grup profilinde gizli tutuluyor.

İkinci müzisyen hesabı **aedrum** oluşturuldu. Kullanıcı henüz Şahbaz'a davet
etmedi veya üyeliği kabul etmedi. Şimdi Üyeleri Yönet ekranının tasarımı
güncellendi. Etkinlik testleri ilerlemedi, B-T1 yeniden oluşturulmayacak.

Üye ekranında sabit 460 px panel ve iç içe kaydırma kaldırıldı. Grup başlığı,
gradient çerçeveli davet butonu, kompakt üye kartları ve kurucu rozeti kullanılır.
Kurucuda çıkarma ikonu yok, diğer üyelerde mevcut onaylı çıkarma akışı korunur.
İşlem kilidi ve parent ekran durum değişikliklerini dinleme ile yenileme/davet
durumu anında yansır. Büyük yazıda yönetim paneli etiketlerinin taşması da
düzeltildi. Backend/veritabanı değişmedi, Hot Restart yeterli.

Doğrulama: 14 yeni üye ekranı testi ve tüm Flutter paketi 1.736/1.736 GEÇTİ.
Statik analiz temiz. Üç tema, %200 yazı, 320 dp ekran, 100 üyeli tembel liste,
boş liste, geç yükleme, yenileme hatası/tekrar deneme, çift davet tıklaması,
çıkarma onayı/iptali test edildi. Gerçek Flutter önizlemeleri incelendi.
Telefonda yeni tasarım henüz kullanıcı tarafından onaylanmadı.

Görsel kontrol sonrası sıradaki adım: bugrasahin → Şahbaz → Yönetim Paneli →
Üyeleri Yönet → Üye davet et ile aedrum davet edilecek. Ardından aedrum
hesabında grup üyeliği daveti kabul edilecek. Etkinlik gösterim tercihlerine
henüz dokunulmayacak. Sonraki test kişisel grup üyesi tercihinin grup ve diğer
üyelerden bağımsızlığıdır.

## Önceki durak — B-T1 gösterme/gizleme geçti, kurucu yönlendirmesi kontrolü

En güncel durak burasıdır. Kullanıcı B-T1 için önce grupta **Profilimde göster**
adımını doğruladı. Etkinlik grup takviminde bir kez göründü, kişisel müzisyen
profiline kendiliğinden eklenmedi. Ardından **Profilimden gizle** adımı GEÇTİ:
grup takviminden kalktı, mekan etkinliği korundu ve onaylı `@Şahbaz` bağlantısı
grup profilini açmaya devam etti.

Yeni yönlendirme hatası: grup kurucusu bu bağlantıya dokununca kendi grubunun
public görünümü açılıyordu. Etkinlik detayı band rotasını koşulsuz public
seçiyordu. Düzeltme, oturumu aktif müzisyenin sabit kullanıcı kimliği ile
grubun ACTIVE FOUNDER üyesini eşleştirerek kendi grup rotasını seçer. Grup
ekranı kendi mevcut yetki kontrolünü tekrar yapar. Diğer kullanıcılar ve
kurucu olmayan üyeler public görünümde kalır. Katılım/yayın verisi değişmez.

Otomatik doğrulama: 29 yeni grup yönlendirme regresyonuyla etkinlik detayının
138 testi ve tüm Flutter paketi 1.722/1.722 GEÇTİ. Statik analiz temiz.
Kurucu/üye/başkası ayrımı, rol ve durum kontrolü, yanlış band kimliği, başarısız
istek ve tekrar deneme, çift dokunma, hesap değişimi, kapanan/örtülen ekran,
geri dönüp yeniden açma ve onaysız bağlantının kapalı kalması kapsandı.

Sıradaki telefon kontrolü: Hot Restart sonrası grubun kurucusu hesabındayken
mevcut B-T1 detayından `@Şahbaz` adına dokun. Kendi grup profili ve Yönetim
Paneli erişimi açılmalı. Geri dönünce etkinlik detayı korunmalı. Bu düzeltmenin
telefon onayı henüz alınmadı. Gösterme/gizleme ve davet adımları baştan
tekrarlanmayacak. Backend yeniden başlatma veya veri geçişi gerekmiyor.

## Önceki durak — B-T1 katılımı onaylandı, grup profilinde gösterim sırada

Bu bölüm en güncel manuel durumdur. Aşağıdaki duraklar geçmiş kayıtlarıdır.
Kullanıcı önceki günün etkinliklerini sildi. Geçen test sonuçları korunur,
eski etkinlikler yeniden oluşturulmayacak.

`B-T1 — Grup katılımı` (7 Eylül 2026, 20:00–22:00) için kullanıcı doğruladı:

- Sanatçı olarak kişisel müzisyen değil Şahbaz grubu seçildi. Grup sahibine
  gruba yönelik bildirim geldi.
- Şahbaz'ın daveti, “Bu etkinliği profilimde de göster” seçilmeden onaylandı.
  Onay başarılı ve davet bekleyenlerden kalktı.
- Mekan etkinlik detayındaki onaylı `@Şahbaz` bağlantısı grup profilini açıyor.
- B-T1 grup profilinin takviminde yok.
- Şahbaz → Etkinlik Yönetimi → Etkinliklerim → Bu Haftaki listesinde tek gizli
  kayıt var. Kullanıcı bu üç son kontrolü de doğru olarak bildirdi.

Görsel ara: etkinlik detayındaki sanatçı/grup ve mekan kutularının sabit 45/55
oranı kaldırıldı. Kısa adlar doğal genişlikte kalır, uzun adlar aynı satırdaki
alanı paylaşır ve gerektiğinde üç noktayla kısalır. Profil bağlantısı ve katılım
yetkileri değiştirilmedi. Bu değişiklik için backend veya veri geçişi gerekmez.

Otomatik doğrulama: 15 yeni yerleşim regresyonuyla tüm Flutter paketi
1.693/1.693 GEÇTİ, statik analiz temiz. Etkinlik detayının 109 testi ve ayrı
gerçek Flutter önizleme testi toplam 110 kontrolle GEÇTİ. Kısa/uzun isim,
dar/yatay ekran, %200 yazı, kalın yazı tercihi, geç yüklenen avatar, tek satır,
tam ad tooltip'i ve onay/yönlendirme davranışları kontrol edildi. Altı gerçek
Flutter ekran görüntüsü görsel olarak incelendi. Telefonda yeni yerleşimin
görsel onayı henüz alınmadı.

Sıradaki manuel adım: Hot Restart ile kutuları kontrol ettikten sonra mevcut
B-T1'in Şahbaz → Etkinliklerim kaydından **Profilimde göster** seçilecek.
Grup takviminde tek kez görünmesi ve kişisel müzisyen profilinin otomatik
yayınlanmaması kontrol edilecek. Bu gösterim adımı henüz GEÇTİ sayılmıyor.

## Önceki durak — mola sonrası bağımsız kalite denetimi

Alt bildirim raporu ve etkinlik akışı bağımsız incelendi. Yeni kurulum migration
listesi, boşalan son takvim sayfası, eski davet callback'leri, sayfalama sınırı,
alt bildirim erişilebilirliği ve geç WhatsApp hata sonuçları düzeltildi.
Flutter tam paketi 1.678 test, backend ilgili geniş paket 408 test ile GEÇTİ.
Ayrıntılar ve denetimin sınırları `EventProductionQualityReview.md` içindedir.

Manuel ilerleme değişmedi: M-T5 ve kendi profiline yönlendirme GEÇTİ, B-T1
henüz başlamadı. Hot Restart sonrası Şahbaz grup bildirimi kontrolü ile devam
edilecek. Mevcut backend/veritabanı durdurulmadı veya değiştirilmedi.

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
