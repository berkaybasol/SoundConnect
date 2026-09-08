# Mekan profili — aktif sanatçı dizini

7 Eylül 2026. Mekan profilindeki **Aktif Sanatçılar → Tümü**, mekan sahibi
ve ziyaretçi görünümünde aynı salt okunur listeyi açar.

## Davranış

- **Sanatçılar / Gruplar** sekmeleri ayrı sorgulanır. Müzisyen araması sahne
  adı ve kullanıcı adını, grup araması grup adını kapsar.
- Arama 300 ms geciktirilir. Sunucuda Türkçe karakter ve büyük/küçük harf
  farkları normalize edilir. `%`, `_` gibi karakterler joker sayılmaz.
- Sayfa boyutu 20. Liste tembel çizilir, görseller sınırlı boyutta önbelleğe
  alınır. Yükleme, boş sonuç, hata, tekrar deneme ve çekerek yenileme vardır.
- Hem güncel mekan ilişkisi hem kabul edilmiş bağlantı isteği gerekir.
  Bekleyen/reddedilmiş istekler ve kaldırılmış ilişkiler gösterilmez.
- Kartlar ortak profil yönlendirme kapısını kullanır. Kendi müzisyen ve
  kurucu/üye grup profili için mevcut sahiplik kontrolleri korunur.
- Sekme/arama/hesap değişince eski yanıtlar uygulanmaz. Sayfalar arasında
  toplam veya kayıtlar değişirse ilk sayfa yeniden alınır. Başka profilden
  geri dönüşte liste yenilenir, yönlendirme sırasında profil sayfasının
  değiştirilmesi de bu yenilemeyi engellemez.

## API

`GET /api/v1/public/venue-profiles/{venueId}/active-artists`

Parametreler: `type=MUSICIAN|BAND`, isteğe bağlı `q`, `page`, `size`.
Arama en fazla 100 karakter, boyut 1–50, sayfa 0–10000. Varsayılan boyut 20.
`BaseResponse.data` içinde sayfa bilgileri ve
`content: [{id, name, type, profilePictureUrl}]` döner.

Sorgu sunucuda filtrelenir ve ad + UUID ile sabit sıralanır. Toplam ve içerik
aynı salt okunur snapshot'ta alınır. Yalnız hazır ve herkese açık görsellerin
URL'leri döner. Özel başvuru notu/bildirim/üyelik yönetimi verisi dönmez.

## Çalıştırma ve test sınırı

Yeni endpoint için backend yeniden başlatılmalı, Flutter hot restart
yapılmalı. Şema değişikliği yok, veritabanını sıfırlamak gerekmez.
Gerçek uygulama veritabanında işlem yapılmadı. SQL testleri açıkça geçici
PostgreSQL Testcontainers veritabanını kullanır.

Doğrulama: 35 backend testi (13 PostgreSQL, 10 HTTP, 12 servis), 41 Flutter
repository testi ve 31 dizin widget testi geçti. Tam Flutter koşusu 2467
testle başarılı, `dart analyze lib test` temiz. 320 dp / %200 yazı / açık
klavye taşması yakalandı ve giderildi. Gerçek widget önizlemeleri incelendi.
Backend test sonuçları `build/venue-active-artists-verification/test-results/test`,
koşu kaydı `build/venue-active-artists-isolated.log` altında.
İki ek profil giriş testi de geçti. Hem owner hem public mekan ekranından
gerçek dizin açılıyor ve profil kaydı ID'si yerine doğru mekan ID'si
gönderiliyor. Bu kontrollerde bulunan eski medya sekmesi metin taşması da
tek satırlık esnek etiketlerle giderildi.

Manuel etkinlik testinde M-T5 bağlı müzisyen akışı geçti. B-T2 bağlı grup
etkinliği henüz oluşturulmadı. Önce bu liste telefonda kontrol edilecek.
