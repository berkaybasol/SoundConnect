# Onaylı üretime hazırlık planı

İlk onay **8 Ekim 2026**, son geçiş kararı **21 Kasım 2026**. Kaynak: bu çalışma alanındaki doğrudan kullanıcı
konuşması; kaynak incelemesi sonrası planın açıklanması, “harikasın ... başlayalım”
onayı ve ardından bu oturumun yalnız hazırlıkla sınırlandırılması.

## Aşama sırası

| No | Kapsam | Aşama çıktısı | Branch |
| --- | --- | --- | --- |
| 01 | Hesap güvenliği, sağlık görünümü ve staging işletimi | Oturumların iptali, güvenli hız sınırı, kurulum ve geri alma, izleme, dış alarm ve kurtarma. Kapasite kabulü yayın öncesine ertelendi. | `ready-for-prod-01` |
| 02 | Admin panelinin işletim işlevleri | Başvuru, kullanıcı, şikâyet, backline ve gerekli operasyon yönetimi; yetki ve kalıcı yönetici işlem izi | `ready-for-prod-02` |
| 03 | Hesap yaşam döngüsü ve ortak kullanıcı güvenliği | Dört profil için silme, kullanıcı engelleme, kalan ortak hesap işlemleri; 02'de yapılan yaptırım/audit tekrar yazılmaz | `ready-for-prod-03` |
| 04 | Google Analytics | Ölçüm planı, güvenli kimlik/tercih yönetimi, test/canlı ayrımı, gerçek cihaz olay doğrulaması | `ready-for-prod-04` |
| 05 | Modül modül prod kalitesi ve gerçek kabul | Kapsamdaki modüllerin doğruluk, yetki/mahremiyet, eşzamanlılık, hata, kaynak sınırı ve gerçek kullanıcı yolu kapanışları | `ready-for-prod-05` |
| 06 | Farklı GitHub repolarına taşıma ve prod altyapı yapılandırması | Geçmiş/kanıt korunumu, GitHub/CI/erişim güvenliği, secret/env/ortam ayrımı, AWS/Firebase ve diğer bağımlılıkların prod uygunluğu | `ready-for-prod-06` |
| 07 | iOS | Apple kimliği/imza, APNs/Firebase, izinler, linkler ve ana ürün yollarının gerçek iPhone kabulü | `ready-for-prod-07` |
| 08 | Bütünleşik üretim ve yayın kabulü | Android+iOS+backend, gerçekçi yük, kesinti/kurtarma, yedekten dönüş ve mağaza sürümü kabulü | `ready-for-prod-08` |

Önceki altı aşamalı plandaki son genel kabul, yeni 06 ve iOS 07 sonrasına 08
olarak taşındı. Bildirimlerin önce bitirilmesi koşulu son kapanışla karşılandı;
Analytics 04'te, iOS 07'de kalır. Tam prod yapılandırmasının 06'da olması,
önceki işlerde gizli bilgi güvenliğini veya test/canlı veri ayrımını ertelemez.

05 için önerilen alt sıra: hesap/profil/medya; DM/masalar/Overthinking;
etkinlik/mekân/grup; Collab/Pazar; stüdyo. Bu, yeni kullanıcı kararı gelirse
değişebilir. İlgili kapalı bildirim kabulünü gereksiz yere tekrar açma.

## Branch ve kapanış düzeni

1. Aşama, son kapanmış ve doğrulanmış ana daldan açılan kendi branch'inde yürür.
   Şu an frontend ana dalı `main`, backend ana dalı `master`.
2. Kaynak/test/build ve gereken gerçek API/cihaz/görsel kabul aynı bağlı işte
   tamamlanır. Controller kullanılıyorsa teknik inceleme/kapanış bağımsızdır;
   developer kendi çalışmasına bağımsız onay verdiğini iddia etmez.
3. Kullanıcı, **her aşama tamamlandığında commit/push ve ana dallara aktarımı,
   ardından sonraki branch'in açılmasını** bu konuşmada yetkilendirdi. Bu kapsam
   için olağan Git yayın iznini tekrar isteme; gereken gerçek görsel/ürün kabulü
   yerine bu yetkiyi kullanma.
4. Onaylı aşama değişikliklerini branch'e commit/push et; PR ve geçerli CI
   kontrolleriyle normal merge kullan. Ana dalda merge sonrası gereken kontrolleri,
   kaynak/uzak ref eşliğini ve çalışma ağacı durumunu kaydet. Başarısız kontrolleri
   atlama, korumaları gevşetme, force-push/reset/clean/stash yapma.
5. İki repo değiştiyse sözleşme uyumunu koruyarak koordineli kapat. Değişmeyen
   repoda boş commit/PR üretme. Yeni branch, her reponun güncel ana dalından açılır.
6. Sonraki branch ve görev/devir kaydı hazırlanabilir; sonraki aşamanın ürün
   geliştirmesi kullanıcı devam talimatıyla başlar. Sırf branch açıldı diye
   sonraki işi kendiliğinden başlatma.

Her büyük aşama tek branch numarası taşır; oturum bölünmesi yeni aşama değildir.
Olağan hatayı yeni developer oturumu açma gerekçesi yapma (K-18). Kullanıcı
özellikle yeni oturum istediğinde bağlamı bu paketle aktar.

## 01 kapanışı ve 02’ye geçiş

21 Kasım 2026’da kullanıcı kapasite kabulünü açık bırakarak 01’in kapanışını tamamlama kararı aldı. Grafana eğitimini ayrıca yürütecek. 01’in PR, CI, birleştirme ve ana dal doğrulaması tamamlandığında 02’ye geçilebilir. [01.8 kapanış özeti](asamalar/01/KAPANIS_01_8_20261121.md)

## Herkese açık yayından önce kapalı test

Android ve iOS yayın adayları hazır olduğunda 05’in modül listesi sürümdeki özelliklerle karşılaştırılacak. Davetli ekip her özelliği ilgili kullanıcı rolleriyle gerçek cihazlarda deneyecek. Android için Google Play kapalı test, iPhone için TestFlight kullanılacak. Yetki, mahremiyet, veri kaybı ve birden fazla özelliği birleştiren akışlar ayrıntılı sınanacak. Sonuçlar, bulunan sorunlar ve denenemeyen durumlar kaydedilecek.

Test ortamında seçilmiş işlemler birlikte çalıştırılarak yanıt süreleri, hatalar ve video işleme kuyruğu ölçülecek. Hedef yük, süre, durdurma koşulları ve bütçe koşudan önce belirlenecek. Kullanıcı yük testi için dış firmayı değerlendiriyor. Test ekibi ve bütçe henüz açık. Her iki çalışmanın sonucu 08’deki yayın kararına girdi sağlayacak.

## 06'nın sınırları

Hedef GitHub hesap/organizasyon ve repo isimleri henüz belirlenmedi. Taşımada
commit/tag/geçmiş, CI, gerekli issue/PR ve kanıt bağları, dokümanlar ve erişimler
envanterlenir. Kopyalama ile GitHub metadata/secret/ayarların otomatik taşındığını
varsayma. Yeni hedef doğrulanmadan eski repo/geçmiş/kanıt silinmez.

GitHub korumaları ve Actions yetkileri; ortam bazlı secret/IAM; test/staging/prod
ayrımı; AWS ağ/depolama/CDN/DB/backup ve Firebase proje/kimlik/erişim/FCM ayarları
birlikte denetlenir. APNs ve iOS'a özel bağlantılar 07'de tamamlanır. Ücretli
ürün/hizmet satın alma, geri döndürülemez hesap değişikliği ve gerçek canlı
dağıtım için somut kullanıcı yetkisi gerekir; bu plan üretime deploy talimatı
değildir. Kullanıcının panel kurulumunu ekran ekran öğrenme tercihi korunur.

## Kapsam ve koruma

Önce mevcut altyapıyı tara, uygun olanı geliştir. Gereksiz paralel sistem veya
geniş refactor oluşturma. İlk sürümde kişiselleştirilmiş profil feed'leri,
sponsorluk/ücretli boost/ödeme kapsamı erteli kalır. Pazar ödeme sistemi değildir.
Onaylı Collab/panel dili, ince gradyan çerçeveli butonlar ve bildirim görünümü
korunur. Tüm kabul/cihaz/servis/veri/kanıt koruma kuralları AGENTS'deki gibi sürer.

Bu paket backend Git reposunda sürümlenir; frontend'deki küçük yönlendirme ve
yerel `hafiza` bunun kopyası değil girişleridir. Repo taşımasında paketin yeni
konumu ve tüm bu girişler birlikte güncellenir.
