# Profil yönlendirmesi ve gruptan ayrılma incelemesi

6 Eylül 2026 — frontend düzeltmesi, mevcut backend sözleşmesi korunur.

## Bildirilen sorunlar ve kök neden

aedrum grup üye listesinden kendi adına dokunduğunda public müzisyen ekranına
gidiyordu. Farklı kaynak ekranlar doğrudan public rota seçtiği için etkinlik
detayındaki önceki yerel düzeltmeler diğer girişleri kapsamıyordu. Grup üyeliği
için backend'de ayrılma işlemi vardı fakat profil ekranında erişilebilir bir
ayrılma eylemi yoktu.

Tarama ayrıca üç üye listesinde eksik profil kimliğini kullanıcı kimliği veya
isim aramasının ilk sonucuyla tamamlama davranışı buldu. Kullanıcı kimliği ve
profil kimliği farklı alanlardır. İsim eşleşmesi doğru insanı kanıtlamaz.

## Uygulanan kurallar

- AppRouter'ın beş public profil rotası ortak ProfileRouteGate üzerinden
  çözülür. Arama, DM, collab, bildirim, bağlantı kartları ve etkinlik bağlantıları
  aynı merkezi kurala tabidir. Etkinlik detayındaki isimsiz doğrudan mekan
  ekranı açılışı da bu rotaya taşındı.
- Aktif kullanıcı kendi müzisyen, mekan, stüdyo veya dinleyici profiline
  dokunduğunda kendi profil ekranına gider. Kimlik eşleşmezse public ekran
  açılır. Grup için aktif kurucu yönetilebilir grup ekranına, diğer aktif üye
  üye görünümüne, dışarıdaki kişi public görünüme gider. İsimden yetki türetilmez.
- Üç grup üye listesi ortak BandMemberProfileResolver kullanır. Profil ve
  kullanıcı kimliği sunucu yanıtında birlikte doğrulanır. Eksik/eski kimlikte
  kanonik kullanıcı-profilleri API'si denenir. Belirsiz veya uyuşmayan yanıt
  başka bir kişiye yönlendirmez. Yalnız aynı anda devam eden sorgular birleştirilir,
  kalıcı sahiplik veya üyelik önbelleği tutulmaz.
- DM'nin eski mekan argümanındaki profileId alanı gerçekte venueId taşır.
  Merkezi rota bunu VenuePublicProfileArgs olarak normalleştirir. Çağıranın
  viewerUserId değeri sahiplik kanıtı olarak kullanılmaz.
- Oturum/hesap/rol değişimi ve geç yanıtlar kontrol edilir. Hatalı kimlik
  yanıtında public ekrana sessizce düşmek yerine tekrar deneme sunulur. Tekrar
  denemede rota erişim kuralları da yeniden uygulanır. Dinleyicinin giriş ve
  profil seçimi korumaları korunur. Kapanan veya başka ekran altında kalan
  giriş eski bir yanıtla sonradan kullanıcıyı başka sayfaya taşımaz.
- Grup profilinde yalnız doğrulanmış ACTIVE ve kurucu olmayan üye için
  **Gruptan ayrıl** gösterilir. Yinelenen/belirsiz üyelik, kurucu veya uygun
  olmayan oturum ayrılma eylemini açmaz. Onay penceresi, tek işlem kilidi ve
  gönderim anında hesap kimliği kontrolü vardır. İptal hiçbir istek göndermez.
- Başarılı ayrılma kişisel takvimleri geçersizleştirir ve yerel üyeliği kaldırır.
  Açık etkinlik yönetimi listeleri de güncellenir. Liste yenilemeleri birleştirilir,
  bekleyen yazma bitmeden yenisi başlamaz, yazma otomatik tekrarlanmaz. Seçili
  dönem korunur ve değişen üyelik sonrası sayfalama ilk sayfadan başlar.
- Grup profili yüklenirken hatalı kimlik, bağlantı hatası, kapanan ekran ve
  hesap değişimi kalıcı yükleme göstergesi bırakmaz. Yan bölümlerin hatası geçerli
  grup profilini ve üyelik işlemlerini erişilemez hale getirmez. Büyük yazıda
  üye kartları, bölüm başlıkları ve medya sekmelerindeki taşmalar giderildi.

## Backend sözleşmesi

Mevcut leaveBand işlemi transaction içinde grubu kilitler, ACTIVE üyeliği
doğrular, kurucunun ayrılmasını reddeder, sadece ayrılan kişinin o gruba ait
kişisel etkinlik gösterimlerini gizler ve üyeliği LEFT yapar. Mekandaki etkinlik
ve grubun katılım onayı değişmez. Diğer üyelerin tercihleri etkilenmez.

İzole test koşusu: BandServiceImplPublicationLifecycleTest (20),
BandServiceImplConsentLockTest (1), BandCalendarServiceTest (14).
**35 test geçti, hata/başarısız/atlanan yok.** Canlı backend veya veritabanı
çalıştırılmadı, durdurulmadı veya değiştirilmedi. Migration gerekmez.

## Doğrulama ve manuel sınır

Yeni testler sahip/kullanıcı ayrımı, tüm profil türleri, gerçek AppRouter
bağlantıları, bozuk kimlikler, hesap değişimleri, kapanan/örtülen ekranlar,
çift dokunma, tekrar deneme, üyelikten ayrılma, yayın listesi yenilenmesi,
320 dp ekran ve %200 yazı boyutunu kapsar.

- Tüm Flutter paketi **1.957/1.957 GEÇTİ**. Önceki 1.736 testlik pakete
  221 yeni regresyon eklenmiştir.
- Flutter statik analizi: **No issues found**.
- Ayrı gerçek-fontlu Flutter önizleme koşusu: **2/2 GEÇTİ**. 390 dp/%100 ve
  320 dp/%200 için grup profili ve ayrılma penceresinin dört çıktısı incelendi.
- Git diff whitespace kontrolü temiz.

Yerel kayıtlar: frontend `build/profile-navigation-leave-full-tests.log`,
`build/profile-navigation-leave-analyze.log`, `build/band-leave-preview-tests.log`
ve `build/band-leave-{profile,dialog}-{1.0,2.0}.png`. Backend test kaydı
`build/band-membership-regression.log` ve ilgili JUnit XML raporlarıdır.
Bu kontroller cihaz üstündeki manuel testi geçmiş saymaz.

Manuel test durağı EventManualTestProgress.md'nin en üstündedir. aedrum hala
Şahbaz üyesi, B-T1 kişisel profilinde görünür ve grup profilinde gizlidir.
Ayrılma testini kullanıcı henüz yapmadı. Yeni veri oluşturulmayacak, mevcut
B-T1 üzerinden devam edilecek.
