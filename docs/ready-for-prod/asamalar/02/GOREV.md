# Aşama 02 — admin panelinin işletim işlevleri

Durum: **HAZIR; ÜRÜN UYGULAMASI BAŞLATILMADI.** Hazırlık tarihi: 8 Ekim 2026.
Bu belge 01'in Git/CI kapanışı sırasında hazırlanabilir; 01'in kapandığı veya
02 branch'inin açıldığı iddiası değildir. Güncel kayıt [DURUM](../../DURUM.md),
onaylı sıra ve Git yetkisi [PLAN](../../PLAN.md). Hedef branch `ready-for-prod-02`.
Ürün çalışması, 01 kapanışı doğrulanıp kullanıcı devam talimatı geldiğinde başlar.

## Amaç ve kalite

Başvuru, kullanıcı, şikâyet ve backline yönetimini mevcut admin panelinde güvenli,
anlaşılır ve kalıcı yönetici işlem iziyle kullanılabilir hale getir. Önce mevcut
altyapıyı tara; uygun yapıyı kullan/geliştir, yetersizse yeni yapının gerekçesini
yaz. Gereksiz paralel RBAC/audit/bildirim hattı veya geniş refactor oluşturma.
Üretime hazır kalite: doğruluk, yetki/mahremiyet, veri tutarlılığı, eşzamanlılık,
hata/retry, kaynak sınırları ve anlamlı doğrulama. Test veya korumaları gevşetme.

## Başlangıç ve mevcut altyapı

AGENTS → BASLA → DURUM → PLAN sırasını izle; iki repo HEAD/branch/diff/staging ve
çalışan ortamı taze doğrula. İlk çalışma kaydında kaynak bulgusunu yeniden üret;
8 Ekim [inceleme kaydı](../../INCELEME-20261008.md) bugünkü kabul yerine geçmez.
Backend yolları `src/main/java/com/berkayb/soundconnect/` altındadır:

- `modules/admin` dashboard/sağlık, `modules/role` mevcut yetki modeli;
  `ADMIN_PANEL_ACCESS`, `READ_USERS` ve işleme özel `MANAGE_*` izinlerini koru.
- `modules/application/{venueapplication,studioapplication}` admin controller,
  karar servisleri ve kalıcı mail intent; stüdyo reviewer/reason alanları mevcut.
- `modules/user/controller/admin` liste/detay/güncelleme; 01'de güvenli hale gelen
  yönetici parola değişimi, atomik oturum sürümü artışı ve HTTP/WS iptali korunur.
- `modules/collab`, `modules/marketplace` şikâyet incelemesi ve
  `modules/backline/catalog` kategori talebi/karar/sürüm kontrolleri mevcut.
- `modules/feed/musician/moderation` işlem geçmişi, request/sürüm benzersizliği
  ve append-only audit örneğini incele; bütün modüllerde aynısı var varsayma.
- Frontend `lib/modules/admin/presentation/screens/admin_dashboard_screen.dart`
  ile mevcut Pazar şikâyeti, sağlık ve bildirim yönetimi ekranlarını geliştir.
  01'in pasif sağlık/eskime, güvenli hata toplama ve startup lifecycle sınırları;
  mevcut kampanya/duyuru ayrımı ve bildirim hedef/okunma güvenliği korunur.

## Yetkili ürün kapsamı

1. Mekân/stüdyo başvuru listesi, detay ve onay/red yolunu tamamla. Gerekçe,
   karar veren, zaman ve doğru başvuru/oluşan profil ilişkisi kalıcı olsun;
   aynı başvuruya tekrar veya eşzamanlı karar çift profil/yan etki üretmesin.
2. Kullanıcı arama/liste/detay ve gerekli yönetici düzenleme/yaptırım akışlarını
   tanımla. Askıya alma/geri açma gerekiyorsa mevcut kayıt/silme durumlarından
   ayrıştır; `INACTIVE` değerini varsayımla ban yerine kullanma. Yaptırımın
   oturum/erişim etkisi ve geri alma sınırı aynı işte doğrulansın.
3. Mevcut şikâyet kararlarını panelde sonuç/gerekçe/geçmişiyle bağla; Collab,
   Pazar ve mevcut moderasyon kurallarını koru. Sırf admin API'si var diye tüm
   özel mesajları tarayan ekran açma; inceleme ilgili şikâyet ve gerekli kanıtla
   sınırlı, yetkili ve izlenebilir olsun. Erteli profil feed'ini etkinleştirme.
4. Backline kategori taleplerini listele/incele/onayla/reddet; katalogda doğru
   tek değişiklik, karar izi ve başvuru sahibine mevcut sonuç yolu tutarlı olsun.
5. Her yönetici mutasyonu backend'de güncel aktör/işlem yetkisiyle korunsun;
   gizlenmiş buton yetki kontrolü değildir. Liste/detay/işlem izinleri ayrı
   sınansın; listener/pending/oturumu iptal edilmiş aktör sınırları korunur.
6. Başarılı işletim değişikliği ile kalıcı audit aynı transaction sonucuna
   bağlansın. Aktör, hedef, işlem, önceki/sonraki durum, zaman, sınırlı gerekçe
   ve istek bağı izlenebilsin; log satırı audit yerine geçmez. Geçmişi sessizce
   ezme; tekrar/çakışma/rollback sonucu belirsiz veya çift karar bırakmasın.

Arama/sayfalama/sıralama ve sorgu maliyeti bounded olsun. Boş/yükleniyor/hata,
yetki kaybı ve eski veriyle karar çatışmasını açık göster. Audit/log/cevaplara
parola, token, iletişim bilgisi veya gereksiz özel içerik taşıma; saklama/erişim
sınırını yaz. Migration'larda eski veri uyumu ve güvenli geri dönüşü açıkla.

## Doğrulama ve Manuel kabul

Yetki reddi, nesne/aktör erişimi, eşzamanlı karar, stale sürüm, tekrar istek,
transaction rollback/audit ve bounded listeleme için anlamlı testler yaz.
Gereken test/build ve bağımsız incelemeyi tamamla; mevcut PASS'leri yeni sayıya
ekleyerek çoğaltma. Her katmanda kaynak SHA, JAR/APK, ortam ve kanıt yolu olsun.

- Gerçek HTTP/JWT ve kalıcılıkla başvuru/şikâyet/backline kararı, kullanıcı
  yaptırımı, yetkisiz erişim, audit ve tekrar isteği doğrula. Yan etkili mail/
  bildirim gerekiyorsa kontrollü hedef kullan; provider kabulü teslim değildir.
- Etkilenen normal mobil yönetici yolu ve yaptırımın kullanıcıya etkisini son
  APK ile fiziksel Vivo'da doğrula; mevcut oturum/veri korunsun. **8 Ekim 01 için
  verilen “bu oturum” emülatör tercihi yeni 02 oturumuna kalıcı muafiyet değildir.**
  Yeni açık kullanıcı kararı varsa onu uygula ve ortamı doğru adlandır.
- Otomatik test, gerçek API/DB, cihaz ve insan görsel onayını ayrı kaydet.
  Son ekranları kullanıcıya göster; önceki 01 onayı yeni akışın onayı değildir.
  Yalnız backend alt kontrolünde telefon gerekmiyorsa gerekçeyi UYGULANAMAZ yaz.

## Sınırlar ve kapanış

02'nin yönetici yaptırımı/audit'i 03'te tekrar yazılmaz; dört profil için hesap
silme, kullanıcıdan kullanıcıya engelleme ve kalan ortak hesap işleri 03'tür.
Analytics 04, genel modül taraması 05, prod yapılandırma/taşıma 06, iOS 07,
genel yayın kabulü 08'de kalır. Ücretli boost/ödeme ve canlı deploy kapsam dışı.
Dirty iş/kanıt/paketler, cihaz verisi ve ortak servisler korunur; yıkıcı testler
sahipli izole fixture'da yapılır. Olağan bağlı regresyon aynı işte çözülür.
Kabul ve bağımsız inceleme sonrası PLAN'ın mevcut Git/CI kapanışını uygula;
çalışma/kabul kaydı → DURUM → BASLA → hafıza devrini güncelle. Sonraki branch
hazırlığı 03 ürün geliştirmesini kendiliğinden başlatmaz.
