# Müzisyen akışı: görülme geçmişi ve sayfalar arası çeşitlilik

14 Eylül 2026.

- Görülme sinyali mevcut `tbl_musician_feed_telemetry_event` içindeki doğrulanmış `IMPRESSION` olaylarıdır. Sayfaya teslim edilmiş veya önden yüklenmiş olmak tek başına görülme sayılmaz. Frontend'in görünürlük eşiği değişmez.
- Aday hedefleri için tek toplu sorgu, kullanıcının son 24 saatte kaydedilmiş olaylarını mevcut teslim defteriyle birleştirir. Sunucunun `recorded_at` zamanı kullanılır. İstemcinin ilettiği saat sıralamayı geriye dönük değiştirmez.
- Üst zaman sınırı akış oturumunun sabit `anchor` anıdır; geç gelen olaylar ve mevcut oturumun gösterimleri sonraki sayfaları tekrar sıralamaz. Başka kullanıcının, duyurunun, sponsorun ve profil tamamlama kartının gösterimi organik görülme sinyaline katılmaz.
- Süresi dolmuş bir teslimat belirteci yeni eylem yetkisi vermez; aynı teslimatın geçmişte kaydedilmiş gösterimi normal saklama süresi içinde geçmiş olarak okunabilir.
- Bu bilgi sıralamada yumuşak azaltma içindir; kalıcı gizleme veya engelleme değildir. Mevcut gizleme/sessize alma/moderasyon filtreleri ayrı uygulanır. Aday sağlayıcılarının sınırlı havuzları dışındaki içeriğin keşfedileceği garantisini vermez.
- Aynı oturumun teslim defterinden son beş organik kartın yazar/tür/havuz bilgisi, en eskiden yeniye sıralı olarak snapshot'a eklenir. Duyuru, sponsor ve profil tamamlama kartları bu pencereyi sıfırlamaz. Yeni kalıcı oturum veya telemetry altyapısı oluşturulmaz.

## Duyuru sıklığı

Yeni duyuru planı seçilirken mevcut `AnnouncementAnalyticsStore` tek toplu okumada toplam nitelikli gösterim, son 24 saatin gösterim sayısı ve son kaydedilme zamanını döndürür. Aynı duyuru son nitelikli gösteriminden itibaren altı saat yeni plana alınmaz; kayan 24 saatte iki gösterime ulaştıysa pencere rahatlayana kadar plana girmez. Tam altıncı saatte bekleme biter; tam 24 saat önceki olay yeni pencerenin dışında kalır. Akış ve duyuru dizini kaynaklarındaki nitelikli gösterimler aynı hesapta birlikte sayılır; detay açma ve video olayları gösterim sayısına eklenmez.

Toplam gösterime göre ağırlık, en yeni uygun duyurunun ilk gösterimine özel erken yerleşim ve oturumdaki en fazla üç duyuru kuralı korunur. Devam sayfası, önceden seçilmiş planın ağırlıklarını veya sıklığını yeniden okumaz. Bu bir **yeni plan kabul politikasıdır**: daha önce aynı anda açılmış oturumların toplamı için transaction kilidiyle uygulanan kesin küresel günlük kota değildir. Gerçek görülme geçmişi bulunmayan bir teslimat beklemeyi başlatmaz. Mevcut `idx_promotion_analytics_actor_history` indeksi kullanılır; yeni duyuru telemetry tablosu veya indeks gerekmez.

## Dağıtım

`scripts/db/2026-09-14-musician-feed-recent-views.sql`, mevcut teslimat migration'ından sonra çalıştırılmalıdır. `scripts/dev.ps1` sırasına eklenmiştir. Script `CONCURRENTLY` kullandığından autocommit açık ve `ON_ERROR_STOP=1` ile, bir transaction'a sarılmadan çalıştırılır. Kullanıcı+zaman için sadece `IMPRESSION` satırlarını içeren indeks ekler; kullanıcı veya içerik verisi eklemez/silmez. Bu çalışma sırasında gerçek veritabanına uygulanmamıştır.

Sorgu en fazla 8.192 aday hedef kabul eder ve üç saniyelik transaction zaman aşımı taşır. Büyük canlı veri üzerindeki sorgu planı ve gecikme, rollout sırasında ayrıca ölçülmelidir; birim test başarısı canlı yük testi sayılmaz.
