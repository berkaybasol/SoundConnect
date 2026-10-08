# SoundConnect Backend — proje hafızası

## Oturum başlangıcı ve devir

8 Ekim 2026: yeni SoundConnect oturumunda [BASLA](docs/ready-for-prod/BASLA.md)
ve gösterdiği güncel DURUM/PLAN/aktif görevi oku. Kullanıcıya geçmişi yeniden
anlattırma. Bu repo ortak üretime hazırlık paketinin sürümlenen kaynağıdır.
Yetkili çalışmada asıl çalışma/kabul kaydı, DURUM ve BASLA ilerledikçe ve oturum
sonunda güncellenir. `ready-for-prod-NN` branch ve aşama kapanış kuralları PLAN'dadır.
Bu giriş tek başına yeni ürün işi veya sonraki aşamayı başlatma talimatı değildir.

## Kod kalitesi ve mevcut altyapı

3 Ekim 2026 açık kullanıcı kararı: üretime hazır (prod-ready) seviyede kod
kalitesi hedefle. Her ihtiyaçta önce mevcut altyapıyı tara; ihtiyacı karşılayan
yapı varsa yeniden yazma, kullan ve gerektiğinde geliştir. Uygun yapı yoksa veya
mevcut yapı yetersizse gerekçesini belirterek gereken yeni yapıyı oluştur. Yeni
kod yasak değildir; gereksiz kopya altyapıdan kaçınılır.

Değişiklikte doğruluk, yetki/mahremiyet, transaction/veri tutarlılığı,
idempotency/eşzamanlılık, hata ve retry davranışı, kaynak sınırları, bakım
kolaylığı ve anlamlı doğrulama gözetilir. Kontrolleri/testleri susturarak işi
kapatma; ilgisiz geniş refactor yapma. Bu kalite hedefi deploy veya üretim kabulü
anlamına gelmez. Controller incelemesi mevcut yapıların kullanımını ve gerektiğinde
yeni yapı gerekçesini değerlendirir.

Üst SoundConnect çalışma alanı mevcutsa durum/karar/öncelik görevlerinde ve çok
adımlı proje çalışmalarında [hafıza dizinini](../hafiza/README.md) oku; yalnız ilgili
ayrıntılara git. Firebase/bildirim/Analytics/yayına hazırlık çalışmalarında
[ana çalışma alanındaki kalıcı kararları](../AGENTS.md) koru.

Yetkili görev sırasında karar veya iş durumu değişirse asıl konu kaydını ve ilgili
hafıza özetini tarih/kaynakla güncelle. Fikir, uygulama onayı değildir; listedeki
işleri kendiliğinden başlatma. Kod/test kabulünü cihaz/üretim kabulü sayma.
Güncel açık kullanıcı talimatı önceliklidir; kayıt çelişkisini sessizce çözme.

Repo tek başına açılmış ve üst çalışma alanı yoksa yerel kaynakları kullan;
ortak hafızaya eriştiğini iddia etme. Günlük başlatma için [README](README.md).

## Bildirim açılışları

30 Eylül 2026 kullanıcı UX kararı; 1 Ekim 2026 kalıcı talimat uygulaması:
resolver/RESULT/receipt/cycle veri sözleşmesi ayrı kullanıcı rapor ekranı
zorunluluğu yaratmaz. Güvenli fresh owned/exact hedef, yetki/session ve read
sözleşmesi korunur; görünür akış mevcut gerçek ürün + gerektiğinde küçük alt
mesajdır, bildirime özel ek rapor/ara sayfa değildir.
Ayrıntı: [esas bildirim açılış politikası](../SoundConnect-Frontend/docs/notification-navigation-policy.md).
Frontend veya üst çalışma alanı yoksa bu kaynağa erişim sınırını belirt;
okuduğunu varsayma.

