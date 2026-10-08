# SoundConnect — yeni oturum başlangıcı

Son güncelleme: **8 Ekim 2026, 12:28 TRT**. Bu paket, onaylı sekiz
aşamanın tek güncel girişidir. Geçmişi kullanıcıya yeniden anlattırma; güncel
kanıt ve yetkiyi aşağıdaki kayıtlardan doğrula.

## Şimdi nerede kaldık?

- **Aşama 01 v1 kapanış sonucu: KAPANDI — TANIMLI 01 V1 KAPSAMI.** Güvenlik ve sağlık/uyarı v1'in
  teknik kabulleri ile final03 APK/gösterilen akışlar için insan onayı tamam.
  İki reponun yayın/ana dal CI/ref sonuçları [DURUM](DURUM.md) içindedir.
- Frontend ana dal `main`, ürün merge SHA `d94487ea47a16e5f2dd3882181182010bc7a48cf`; backend ana dal
  `master`, ürün merge SHA `05d0e11e15d6d7523d4274e4c9caca4e4b0102f9`. Fiilî checkout/HEAD ve uzak eşliği
  yeni oturumda salt okunur doğrula; önceki branch veya dirty durumu varsayma.
- **Somut sonraki adım: 02'den önce kapsamlı observability ve bilgisayar
  kapalıyken de alarm için bağımsız izleme/barındırma/maliyet görüşmesi.**
  Kullanıcının son kararı budur. [02 görevi](asamalar/02/GOREV.md) hazırdır;
  02 branch geçişi ve ürün geliştirmesi bu görüşmeden önce yapılmaz.
- Yeni izleyici, hizmet satın alma veya monitoring stack kurulumu başlamadı.
  Bu devir ve görüşme, bunları kendiliğinden uygulama yetkisi değildir.

Bu SHA'lar doğrulanmış ürün yayını kesimidir; sonradan yalnız kapanış belgeleri
commit edilebilir. Güncel belge HEAD'i ve çalışma ağacı Git'ten ayrıca okunur.

## Kabulün kapsamı

01; reset ve yönetici parola değişimi sonrası eski HTTP/WS oturumlarının
reddi, Redis limiter kesintisinde güvenli hata, pasif admin sağlık görünümü,
güvenli mobil hata alımı ve kontrollü kesinti/toparlanma alarmı temelidir.
Gerçek API/DB/realtime, mobil ve harici mail kanıtları
[MANUEL_KABUL](asamalar/01/MANUEL_KABUL.md) içinde ayrı tutulur.

Son normal APK SHA-256:
`8122a81c10d8fc272a342faf1a61111eb806a9101dc8888abcbeef2bf0e67b34`.
Bu oturumdaki açık kullanıcı tercihiyle mobil kabul Android Studio emülatöründe
yapıldı; Vivo kullanılmadı. Bu istisna sonraki oturumlar için kalıcı fiziksel
cihaz muafiyeti değildir. Kullanıcı final03 ve gösterilen sağlık/reset-yeniden
giriş yollarını doğrudan onayladı; fiziksel veya üretim kabulü çıkarılmaz.

İki TEST alarmı iki alıcı için dört provider teslimi ve bağlı Gmail'de iki
INBOX kaydıyla doğrulandı. Tam PC/elektrik/ağ kesintisi, sürekli bağımsız host
izlemesi ve üretim alarm işletimi kabul edilmedi. CPU/RAM/JVM/disk geçmişi,
p95/p99, merkezi backend log araması ve dağıtık trace henüz uygulanmadı.

## Kısa okuma sırası

1. Çalışma alanı ve ilgili reponun `AGENTS.md` dosyaları.
2. [DURUM.md](DURUM.md): son yayın kimlikleri, kanıtlar, açık sınırlar ve sonraki adım.
3. [PLAN.md](PLAN.md): sekiz aşama, Git kapanış yetkisi ve son 02 bekletme kararı.
4. Gerekirse [01 çalışma kaydı](asamalar/01/CALISMA.md),
   [gerçek kabul](asamalar/01/MANUEL_KABUL.md) ve
   [bağımsız inceleme](asamalar/01/BAGIMSIZ_INCELEME.md).
5. 02 kapsamı görüşülürken [hazır görev](asamalar/02/GOREV.md).
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
> 02 ürün işini veya yeni izleme kurulumunu kendiliğinden başlatma.
