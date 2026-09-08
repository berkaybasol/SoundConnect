# Bekleyen grup davetleri — 7 Eylül 2026

Üyeleri Yönet ekranındaki aktif üyelerin altına **Bekleyen Davetler** bölümü
eklendi. Davet kartı avatar, kullanıcı adı ve **Onay bekliyor** satırından oluşur.
Bekleyen davet yoksa bölüm gösterilmez. Bekleyen kişi aktif üye sayısına dahil
edilmez ve kartından rol düzenleme, üye çıkarma veya profil işlemi yapılmaz.

## 7 Eylül — bekleyen davet fotoğrafı düzeltmesi

Kullanıcı liste tasarımını telefonda doğruladı ancak aedrum'un fotoğrafı görünmedi.
Salt okunur yerel PostgreSQL kontrolünde davet PENDING, eski User.profilePicture
boş, müzisyen profilinin media ID'si dolu bulundu. İlgili medya IMAGE, READY ve
PUBLIC durumda, thumbnail/playback alanları dolu. Sorun davetin gönderim tarihi
veya bozuk fotoğraf kaydı değil, endpoint'in eski User alanını okumasıydı.

- Sorgu artık müzisyen profiline left join yaparak yalnız kimlik, kullanıcı adı
  ve müzisyen fotoğrafının dahili media ID'sini projekte eder. Fotoğrafı/profili
  olmayan davet kaybolmaz. User veya başka profil türünün resmi yedek olarak
  kullanılmaz.
- Kurucu yetkisi denetlendikten sonra yalnız mevcut sayfanın benzersiz medya
  kimlikleri tek toplu sorguda çözümlenir (en fazla 50). Boş/fotoğrafsız sayfada
  medya sorgusu yapılmaz. Kişi başına profil veya medya çağrısı yoktur.
- Mevcut medya servisi yalnız PUBLIC, READY ve görüntülenebilir URL verir.
  Eksik/gizli/hazır olmayan fotoğraf null kalır. Gerçek veritabanı hatası başarılı
  boş fotoğraf sonucu gibi gizlenmez, mevcut liste hata/tekrar dene akışına gider.
- Dış API'nin dört alanı, sayfalama metaverisi, read-only snapshot ve no-store
  davranışı değişmez. Dahili medya kimliği istemciye gönderilmez. Flutter üretim
  kodunda değişiklik gerekmez.
- Bağımsız salt okunur inceleme ek bulgu göstermedi. Mevcut Flutter bekleyen
  davet paketi yeniden **67/67** geçti. Log: `build/band-pending-avatar-tests.log`.
- Güncel backend kaynaklarıyla **197 test geçti, 0 hata**. Bekleyen davet
  kapsamındaki **76 testin tamamı** geçti: controller 13, gizlilik 2, gerçek H2
  sorgusu 8, servis 40, gerçek toplu medya görünürlük filtresi 13. Önceki başlık
  ve takvim/üyelik yayın regresyonları da geçti. Docker erişimi gerektiren dört
  eski PostgreSQL başlık geçişi testi atlandı (201 keşfedilen test).
- Ana Java ve test kaynakları yeni `band-pending-avatar-*` izole klasörlerinde
  derlendi. Derleme sonlandırmasındaki bilinen Windows JAR erişim kilidinden
  sonra yalnız yeni üretilmiş JAR/sınıflarla testler çalıştırıldı ve test görevi
  başarılı tamamlandı. Normal backend build klasörüne dokunulmadı. Loglar:
  `.local-verification/band-pending-avatar-compile-tests.log` ve
  `.local-verification/band-pending-avatar-test-execution.log`. Sonuç XML'leri:
  `.local-verification/band-pending-avatar-build/test-results/test`.

Mevcut davete, üyeliğe veya etkinlik tercihine yazılmadı. Veri geçişi, yeniden
davet veya yeni uygulama kurulumu gerekmez. Backend doğrulaması tamamlandı.
Kullanıcı backend'i yeniden başlatıp Üyeleri Yönet sayfasını yenilemeli.
aedrum henüz daveti kabul etmemeli, önce fotoğraf kontrolü yapılmalı.

## Veri ve yenileme

- Ayrı, kurucuya özel read-only endpoint kullanılır. Detaylı sözleşme
  `BandPendingInvitationsApi.md` içindedir. Public/aktif üye yanıtı genişletilmez.
- İlk 20 kayıt getirilir. Sonraki sayfa **Daha fazla göster** ile istenir,
  tembel oluşturulan sliver listesinde gösterilir. Üye başına profil isteği yoktur.
- Davet gönderilince mevcut profil yenilemesi bekleyen listeyi de yeniler.
  Ekranı yeniden açmak, yenile düğmesi, aşağı çekmek veya uygulamaya geri dönmek
  güncel durumu getirir. Sürekli sorgulama, timer veya arka plan işi yoktur.
- Uygulamaya dönüşte menü/editör/üye profili açıksa yenileme kaybolmaz.
  Aynı oturumda mevcut eylem bittiğinde bir kez yapılır. Hesap değişimi bu
  ertelenmiş yenilemeyi iptal eder.
- Kabul/ret sonraki güncel okumada bekleyen listeden kalkar. Kabul edilen kişi
  mevcut aktif üye yanıtından gösterilir. Bu özellik gerçek zamanlı bildirim
  aboneliği veya yeni bir davet kabul/ret/yayın yazımı eklemez.

## Güvenlik ve tutarlılık

- Liste sadece aktif müzisyen kurucuya görünür. Backend güncel veritabanı rol,
  hesap ve üyelik durumunu ayrıca doğrular. Başkasının bekleyen daveti public
  profil verisine, üyelik yetkisine veya kişisel etkinlik tercihine dönüşmez.
- İstek gönderiminde hesap kimliği, yanıtta oturum nesnesi, grup ve istek
  nesli denetlenir. Kapanmış/değişmiş ekranın geç yanıtı listeye uygulanmaz.
- Yenileme eski sayfaları geçersiz kılar. İlk yükleme ve sonraki sayfa hataları
  başarılı boş yanıt gibi gizlenmez. Sonraki sayfa ağ hatasında mevcut satırlar
  korunur, tekrar dene aynı sayfayı ister. Yetki hatasında özel satırlar temizlenir.
- JSON türleri, PENDING durumu, kişi kimliği, sayfa/sıra/ilk/son/toplam alanları
  ve beklenen kayıt sayısı doğrulanır. Hatalı boş veya kısa sayfa gerçek sıfır
  davet gibi kabul edilmez. Normalizasyon ve sayfa sınırları istemci/sunucuda vardır.
- Sayfalar arasında toplam değişirse veya önceki sayfayla kesişim oluşursa,
  liste ilk sayfadan bir kez yenilenir. Böylece kabul edilen kişi eski listede
  tutulmaz veya kaymış sayfa sınırındaki bekleyen kişi sessizce atlanmaz.
- Aktif üye kimliğiyle çakışan bekleyen satır çizilmez. Böyle bir ilk sayfada
  daha fazla kayıt varsa sonraki sayfaya erişim korunur.

## İlk liste özelliğinin önceki doğrulama ve dağıtım kaydı

Backend: yeni özellik için 53 test ve önceki regresyonlarla birlikte
**174 geçti, 0 hata**. Önceden var olan dört Docker/PostgreSQL başlık geçişi
testi bu çalışmada atlandı. Yeni sorgunun dört repository testi izole H2'de
gerçek sorgu ile çalıştı, mevcut yerel PostgreSQL verisine dokunulmadı.

Ana Java ve test kaynakları izole alanda derlendi. Daha önce kaydedilen Windows
geçici JAR sonlandırma kilidi nedeniyle Gradle görev sonlandırması hata verdi,
güncel derlenmiş sınıflar izole sınıf yoluyla test edildi. Normal backend build
klasörü ve kullanıcının açık backend'i değiştirilmedi. Backend read-only audit
ek bulgu göstermedi.

Frontend odaklı paket **128/128** geçti (67 yeni bekleyen davet testi dahil).
Üç temada 320 dp normal/%200 yazı için altı gerçek-font önizlemesi üretildi,
normal ve büyük yazılı davet kartları görsel olarak incelendi.
Son tam Flutter paketi **2.179/2.179** geçti. Son statik analiz sorun bulmadı,
iki depoda `git diff --check` temiz.
Loglar frontend
`build/band-pending-*.log` ve çalışma alanı
`.local-verification/band-pending-test-execution.log` dosyalarındadır.

**Yeni veritabanı geçişi gerekmez.** Kullanıcı kendi backend'ini güncel kaynakla
yeniden başlatmalı ve Flutter Hot Restart yapmalıdır. Uygulamada gerçek davet
gönderme/kabul etme bu geliştirme sırasında yapılmadı. Manuel testin güncel
durak kaydı `EventManualTestProgress.md` başındadır.
