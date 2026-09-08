# Etkinlik akışı ve alt bildirim denetimi

6 Eylül 2026. Kod kontrol noktaları: frontend `7cd5ed0`, backend `6b995fb`.
Diğer oturumun alt bildirim raporu kaynak iddiası olarak okundu, sonuçları kod ve
yeniden çalıştırılan testlerle bağımsız kontrol edildi. Bu rapor bütün uygulama
için sınırsız bir üretime çıkış onayı değildir.

## Kapatılan sorunlar

1. **Yeni kurulumda eksik geçiş:** `scripts/dev.ps1`, son etkinlik profil yayını
   geçişini çalıştırma listesine almıyordu. Son bağımlılık olarak eklendi. Liste
   sırası, tekil kayıt ve dosyaların varlığı için regresyon testi eklendi. Eski
   TableGroup kurulum testinin güncelliğini yitirmiş başarı metni de düzeltildi.
   Script çalıştırılmadı, mevcut kullanıcı veritabanına migration uygulanmadı.
2. **Boşalan son takvim sayfası:** Sunucu boş son sayfayı `visible=false` ile
   döndürünce ilk sayfadaki geçerli etkinlikler de kaybolabiliyordu. Son sayfa
   tükendiğinde ilk sayfa bir kez güncelleniyor. Kişisel ve grup takvimi korunuyor.
3. **Eski davet işlemleri:** Yenileme sırasında önceki kartın onay/ret callback'i
   kullanılabiliyordu. İşlem güncel yükleme ve güncel nesneyle sınırlandı.
   Yenileme sırasında seçim/karar kapalı. Çakışan sayfalar sunucunun en yeni
   karar iznini koruyor, mevcut sıralamayı değiştirmiyor.
4. **Sayfalama sınırı uyuşmazlığı:** Sunucu 0–100 sayfa indeksini desteklerken
   istemci 101'i isteyebiliyordu. Davet ve profil yayını ekranları desteklenmeyen
   isteği göndermiyor. Liste bitmiş gibi davranmak yerine görüntüleme sınırını
   açıkça bildiriyor. Yenileme, önceki sayfa ve mevcut dönem seçenekleri korunuyor.
5. **Alt bildirim taşması:** Güvenli alan, yatay/küçük ekran ve büyük yazıda
   aksiyon ekran dışına çıkabiliyordu. Gerçek kullanılabilir genişlik kullanılıyor.
   Gerektiğinde yalnız uzun mesaj kayıyor, aksiyon dışarıda erişilebilir kalıyor.
   Mesaj, süre, kuyruk, kapanış nedeni, aksiyonun bir kez çalışması ve erişilebilirlik
   davranışları korunuyor. Normal kısa bildirim doğal yüksekliğini koruyor.
6. **WhatsApp açılış hatası:** Bekleyen üyelik ekranında platform hatası
   yakalanmıyordu ve kapanmış/başka ekranla örtülmüş sayfaya geç sonuç yazılabiliyordu.
   Hata aynı mevcut mesajla karşılanıyor. Geç sonuç kapalı veya örtülü sayfaya yazılmıyor.

## Yeniden doğrulama

- Flutter tam paket: **1.678 geçti**, başarısız test yok. Diğer oturumun 1.657
  testlik sonucuna ek olarak 21 yeni istemci regresyonu dahil.
- Son `flutter analyze --no-pub`: **No issues found**. Son analizde yakalanan
  test yardımcı kodundaki tek süslü parantez uyarısı giderildi.
- Etkinlik/takvim odaklı ilk çalışma: 64 geçti. Sonradan eklenen 4 sayfa sınırı
  regresyonu da tam pakette geçti.
- Alt bildirim ve WhatsApp odaklı çalışma: 32 geçti.
- Gerçek Flutter görsel üretimi: 1 geçti. Açık/lacivert tema, 560×280 yatay
  ekran ve klavye açık büyük yazı örnekleri incelendi. Siyah tema da testlerde var.
- Backend: **408 test / 46 sınıf**, sıfır başarısızlık, hata veya atlama.
  398 kapsam testi ve 10 ayrı aşağı akış bildirim testi.
- Gerçek servis entegrasyonları: 113 PostgreSQL, 3 Redis, 2 RabbitMQ testi.
  Çakışan yeniden onay, transaction rollback, eski bildirim lease'i, tekrar deneme
  sınırı ve commit sonrası bildirim kurtarma için yeni regresyonlar dahil.
- Backend ana kodu ve testler izole derleme dizininde yeniden derlendi.
- PowerShell scripti çalıştırılmadan AST sözdizimi kontrolünden geçti.
- Her iki repoda `git diff --check` temiz.

İstemci kayıtları `SoundConnect-Frontend/build/production-audit-full-tests.log`,
`production-audit-analyze.log`, `production-audit-snackbar-tests.log` ve
`production-audit-event-tests.log` dosyalarındadır. Görseller aynı dizinde
`production-audit-snackbar-*.png` adlarıyla bulunur. Bunlar Git dışında yerel çıktılardır.

Backend XML raporları çalışma alanının `.local-verification` dizinindedir:

- `event-quality-broad-398/test`
- `event-quality-notification-10/test`

Son HTML raporu yalnız ikinci 10 testi içerir. Toplam 408 sonucu iki XML rapor
kümesi birlikte doğrular. Kullanıcının backend süreci veya veritabanı kullanılmadı.

## Korunan kurallar ve sınırlar

Etkinliği yalnız mekan oluşturur. Katılım ve profilde gösterim ayrı kararlardır.
Onaysız bağlantı açılmaz. Reddedilen katılım yalnız başlamadan yeniden onaylanır.
Sonraki gizleme, eski onay isteğinin tekrarıyla geri alınmaz. Aktif grup kurucusu
yetkisi ve üyelerin kişisel gösterim tercihleri bağımsızdır. Üyelikten ayrılıp
yeniden katılma eski kişisel yayın iznini canlandırmaz. Genel takvim anahtarı yoktur.

Bu inceleme tüm backend repository testlerini veya üretim yük testini kapsamaz.
API'nin mevcut 0–100 sayfa sınırı korunur. Çok uzun arşivler için daha geniş
erişim/cursor tasarımı ayrı ölçekleme işidir. Kullanıcının istediği mağaza URL
yer tutucuları bilerek korunmuştur. Bunlar gerçek mağaza bağlantıları değildir.

Gerçek cihazdaki B-T1 ve devamındaki grup/üye senaryoları henüz GEÇTİ sayılmaz.
M-T5 ve kendi profiline yönlendirme manuel olarak zaten geçti. Sıradaki adım
Hot Restart sonrası B-T1 grup bildirimi kontrolüdür. Bu değişiklikler için mevcut
test veritabanını sıfırlamak veya çalışan backend'i yeniden başlatmak gerekmez.
Bu denetimde commit/push yapılmadı, önceki kontrol noktaları korunuyor.
