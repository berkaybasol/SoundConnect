# SoundConnect — yeni oturum başlangıcı

**21 Kasım 2026.** Aşama 01’in işletim kabulleri bağımsız teknik incelemeden geçti. Ürün PR’ı ve ana dalın CI sonuçları 01.8 kapanış özetinden izlenebilir. [01.8 kapanış özeti](asamalar/01/KAPANIS_01_8_20261121.md)

01.7 kapasite kabulü açık kalıyor. Kullanıcı, kapasite ölçümünü yayın öncesine bıraktı ve Grafana eğitimini ayrı yürütecek. 01’in Git kapanışı doğrulandığında 02’ye geçilebilir. [02 görevi](asamalar/02/GOREV.md)

Aşağıdaki v1 sonuçları 8 Ekim’deki güvenlik ve sağlık kabulüne aittir. O tarihteki ürün sürümleri ile PR ve ana dal kontrolleri [DURUM](DURUM.md) belgesinde kayıtlıdır.

## 8 Ekim 2026 v1 kabulü

01; reset ve yönetici parola değişimi sonrası eski HTTP/WS oturumlarının
reddi, Redis limiter kesintisinde güvenli hata, pasif admin sağlık görünümü,
güvenli mobil hata alımı ve kontrollü kesinti/toparlanma alarmı temelidir.
Gerçek API/DB/realtime, mobil ve harici mail kanıtları
[MANUEL_KABUL](asamalar/01/MANUEL_KABUL.md) içinde ayrı tutulur.

Son normal APK SHA-256:
`8122a81c10d8fc272a342faf1a61111eb806a9101dc8888abcbeef2bf0e67b34`.
Bu oturumdaki açık kullanıcı tercihiyle mobil kabul Android Studio emülatöründe
yapıldı. Bu istisna sonraki oturumlar için kalıcı fiziksel
cihaz muafiyeti değildir. Kullanıcı final03 ve gösterilen sağlık/reset-yeniden
giriş yollarını doğrudan onayladı; fiziksel veya üretim kabulü çıkarılmaz.

İki TEST alarmı iki alıcı için dört provider teslimi ve bağlı Gmail'de iki
INBOX kaydıyla doğrulandı. Tam PC/elektrik/ağ kesintisi, sürekli bağımsız host
izlemesi ve üretim alarm işletimi kabul edilmedi. CPU/RAM/JVM/disk geçmişi,
p95/p99, merkezi backend log araması ve dağıtık trace henüz uygulanmadı.

## Kısa okuma sırası

1. Çalışma alanı ve ilgili reponun `AGENTS.md` dosyaları.
2. [DURUM.md](DURUM.md): son yayın kimlikleri, kanıtlar, açık sınırlar ve sonraki adım.
3. [PLAN.md](PLAN.md) sekiz aşamayı, Git kapanışını ve yayın öncesi test kararını açıklar.
4. Gerekirse [01 çalışma kaydı](asamalar/01/CALISMA.md),
   [gerçek kabul](asamalar/01/MANUEL_KABUL.md) ve
   [bağımsız inceleme](asamalar/01/BAGIMSIZ_INCELEME.md).
5. Aşama geçişi için [02 görevi](asamalar/02/GOREV.md).
   Eski [kaynak incelemesi](INCELEME-20261008.md) tarihsel başlangıç bulgusudur;
   bugün açık kusur veya tamamlanmış kabul yerine kullanılmaz.

## Devralırken ve çalışırken

Üst `SoundConnect/` klasörü Git reposu değildir; frontend ve backend ayrı
repolardır. Komutları doğru repoda çalıştır. İki repo HEAD/branch/diff/staging
ve untracked durumunu taze oku. Beklenmeyen farkın kaynağını belirle; kullanıcı
işini, kanıtları, APK/JAR geçmişini, cihaz oturumlarını ve ortak servisleri koru.
Çalışan ortamın önceki kabuldeki durumda kaldığını varsayma.

Doğrulanmış kapanışı teknik kontrolleri baştan başlatma gerekçesi yapma.
Yeni yetkili işte önce asıl çalışma/kabul kaydı, sonra DURUM ve BASLA güncellenir;
karar değişirse PLAN ve proje hafıza özeti kaynakla bağlanır. Ayrıntılı devir için
[DEVIR_SABLONU](DEVIR_SABLONU.md) kullanılır. Teknik test, API/kalıcılık,
cihaz, insan onayı, CI ve üretim kabulü birbirinden türetilmez.

## Yeni oturum için tek mesaj

> SoundConnect-Backend/docs/ready-for-prod/BASLA.md dosyasını oku. Güncel durumu
> doğrula; son kullanıcı kararı ve burada kayıtlı somut sonraki adımdan devam et.
> 02 ürün çalışması kullanıcı devam talimatıyla başlar.
