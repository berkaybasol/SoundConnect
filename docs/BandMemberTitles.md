# Grup üyelerinin serbest başlıkları

6 Eylül 2026. Başlık bir üyeliğe ait görünüm bilgisidir, yönetim yetkisi değildir.

## 7 Eylül 2026 — yerel geçiş tamamlandı

Kullanıcı backend'i durdurduğunu teyit etti. Docker Compose hedefi proje yolu,
yerel ortamın `soundconnectdb` / `postgres` seçimi ve PostgreSQL bağlantılarıyla
doğrulandı. Yalnız boşta pgAdmin bağlantısı vardı, backend bağlantısı yoktu.

- Tam custom-format `pg_dump` yedeği alındı ve arşiv listesi doğrulandı:
  `../.local-verification/backups/band-titles-20260907-105024/soundconnectdb.dump`.
- Yedek ayrı, bu işlem için oluşturulan PostgreSQL veritabanına geri yüklendi.
  Başlangıç verisi karşılaştırıldı. Başlık geçişi iki kez başarıyla uygulandı.
- Ardından yalnız `2026-09-06-band-member-titles.sql` asıl yerel veritabanına
  uygulandı. 84 public tablonun sıralı satır JSON özetleri önce/sonra aynı kaldı.
  Yeni `title_version` alanı ve geçiş kayıt tablosu karşılaştırmaya dahil edilmedi.
  Önceden var olan `member_title` dahil tüm eski alanlar karşılaştırıldı.
- `title_version bigint NOT NULL DEFAULT 0`, sıfır geçersiz sürüm ve tek geçiş
  kaydı doğrulandı. Üyelik, rol ve etkinlik/yayın kayıtları değişmedi.
- Doğrulama için oluşturulan geçici veritabanı kaldırıldı. Dosya yedeği korundu.
  Asıl veritabanı sıfırlanmadı. Backend kullanıcıya bırakıldı, başlatılmadı.

Bu gerçek PostgreSQL geri yükleme/geçiş denemesi geçti. Önceki dört atlanmış
JUnit/Testcontainers testi yeniden çalıştırılmış sayılmaz. Telefon testi için
kullanıcı güncel backend'i başlatıp uygulamada Hot Restart yapabilir.

## Ürün davranışı

- Aktif kurucu, Üyeleri Yönet ekranındaki üç nokta → Rolü düzenle yolundan kendisinin ve aktif üyelerin
  gruptaki rolünü serbest metin olarak düzenler. Hazır seçenek listesi yoktur.
- En fazla **20 görünen karakter** kullanılabilir. Türkçe harfler ve birleşik
  emojiler görünür karakter olarak sayılır. Boş bırakıp kaydetmek başlığı siler.
- Kurucunun bağımsız **Kurucu** rozeti korunur. Diğer üyelerde başlık yoksa
  genel bir “Üye” alt yazısı gösterilmez. Kullanıcı adı ve profil bağlantısı kalır.
- Başlık grup üyeliğine aittir. Başka gruptaki başlığı, kişisel profili, üyelik
  yetkilerini veya etkinlik katılım/yayın tercihlerini değiştirmez.
- Grup profili, üye yönetimi ve davet kararındaki üye listeleri aynı başlığı
  gösterir. Uzun metin tek satırda kısalır ve tam metin Tooltip ile erişilebilir.
- Üye kartında yalnız isim/avatar, başlık/kurucu rozeti ve tek üç nokta düğmesi
  görünür. Profil oku ve ayrı düzenle/çıkar düğmeleri kaldırılmıştır. Düzenleme
  ve çıkarma mevcut ortak yönetim bottomsheet tasarımında toplanır. Kurucuda
  çıkarma seçeneği yoktur. Üç noktanın dokunma alanı en az 48 px kalır.
- Menü açıkken çift açılış engellenir. Seçim sonrası oturum, grup, hedef üyelik
  ve sürüm tekrar denetlenir. Çıkarma onayı açıkken hesap veya üyelik değişirse
  onaydan sonra istek gönderilmez. Menü seçimi tek başına üyeyi çıkarmaz.
  Menü yalnız aktif müzisyen oturumundaki aktif kurucuya görünür.

## API ve güvenlik

`PATCH /api/v1/user/bands/{bandId}/members/{userId}/title`

```json
{"memberTitle":"Vokal / Gitar","expectedTitleVersion":0}
```

`memberTitle` açıkça gönderilmelidir. `null` temizler, eksik alan reddedilir.
`expectedTitleVersion` negatif olmayan JSON tam sayısıdır. Tür dönüşümü yapılmaz.
Yanıt `BaseResponse.data` içinde mevcut `BandMemberResponseDto` ve ek olarak
`memberTitle`, `titleVersion` alanlarını taşır. Yanıt önbelleğe alınmaz.

- HTTP katmanı aktif müzisyen oturumu ister. Servis, veritabanından aktif,
  e-postası doğrulanmış kurucuyu ve aynı grubun aktif hedef üyeliğini doğrular.
  İstemcinin gönderdiği rol veya görünen başlık yetki kanıtı değildir.
- Mevcut grup satır kilidi, üyelik değişiklikleri ve başlık yazımını sıralar.
  Beklenen sürüm eşleşmezse 409 döner. Kör tekrar veya son yazanın kazanması yoktur.
- Kanonik başlık değişmiyorsa yazma ve sürüm artışı yapılmaz. Değişiyorsa yalnız
  başlık ve sürüm güncellenir. Sürüm taşmasına karşı kontrol vardır.
- Yeniden davet ve daveti kabul, eski başlığı temizler ve başlık zaten boş olsa
  bile sürümü artırır. Önceki üyelik döneminden açık kalmış editör yeni üyelikte
  başlık atayamaz. Tekrarlanan aktif üyelik kabulü eski kuralla reddedilir.
- Sunucu Unicode boşlukları kırpar, NFC normalizasyonu yapar ve 20 grapheme
  sınırını uygular. İş yükü için ham giriş ayrıca 256 code point / 512 UTF-16
  sınırındadır. Normalizasyon sonrası saklama sınırı da denetlenir.
- Kontrol, satır ayırıcı, bozuk surrogate ve yön değiştirme karakterleri
  reddedilir. Normal yazı/emoji için gerekli joiner karakterleri korunur.
  Yalnız görünmez işaretlerden oluşan başlık kabul edilmez. Metin düz yazı çizilir.
- Uygulama istek ve yanıtta oturum kimliğini denetler. Hesap değişimi, kapanan
  ekran, değişmiş üyelik veya belirsiz yanıt başarı gibi gösterilmez. Çakışmada
  güncel liste bir kez alınır, yazma otomatik tekrarlanmaz.

Hatalar: 9214 geçersiz başlık (400), 9215 eski sürüm (409), 9216 yetkisiz
düzenleme (403). Mevcut üye/grup bulunamadı ve aktif olmayan üye kuralları korunur.

## Ölçek ve veri geçişi

Başlık mevcut üye yanıtına iki küçük alan olarak eklenir. Üye başına ek ağ
isteği, periyodik sorgulama veya yeni arama indeksi oluşturulmaz. Kaydetme yalnız
kullanıcı eyleminde çalışır. Bu özellik için bütün grupları taramak gerekmez.
Bu değerlendirme yük testi yerine geçmez ve mevcut üye listesinin ölçek sınırını
değiştirmez.

`scripts/db/2026-09-06-band-member-titles.sql` eklemeli ve tekrar çalıştırılabilir
bir transaction'dır. `member_title` ve `title_version` sütunlarını ekler, eksik
eski sürümleri sıfır ile tamamlar. Var olan başlık/sürüm, rol, üyelik, etkinlik ve
yayın verisini silmez. Kısa kilit ve çalışma süresi sınırları vardır.
`scripts/dev.ps1` yerel geçiş listesine eklenmiştir.

Geçiş **yeni backend başlatılmadan önce** uygulanmalıdır. Kullanıcının backend'i
durduğu doğrulandıktan sonra yerel veritabanı yedeği alınır, yalnız bu geçiş
uygulanır ve üyelik/etkinlik kayıtları karşılaştırılır. Veritabanı sıfırlanmaz.
6 Eylül'deki ilk doğrulamada geçiş uygulanmamıştı. 7 Eylül'de kullanıcı teyidiyle
tamamlanan yerel uygulama ve yedek bilgisi belgenin başındadır.

## Doğrulama

7 Eylül kart sadeleştirmesi: 61 odaklı Flutter menü/rol/üye ekranı testi geçti.
Son tam Flutter paketi **2.112/2.112** geçti. Son statik analiz ve
`git diff --check` temiz.
Testler menü açıkken ve çıkarma onayı sırasında oturum değişimini, eski
menü seçimlerini, çift açmayı, iptali ve tek 48 px eylem alanını kapsar.
Üç temada kart/menü/editör için gerçek-font önizlemeleri üretildi, normal
ve %200 yazılı dar ekranlar görsel olarak incelendi. Bu revizyonda backend
kodu veya veritabanı değiştirilmedi. Loglar frontend `build/band-member-menu-*.log`.

İlk başlık özelliğinin doğrulaması:

- Backend odaklı paket: **121 geçti, 0 hata**. Eski takvim/yayın/kilit
  regresyonlarının 35 testi bu sayıya dahildir.
- Dört PostgreSQL geçiş testi Docker bulunmadığı için **atlandı**. Geçişin
  gerçek PostgreSQL üzerinde çalışması henüz doğrulanmış sayılmaz.
- Backend ana kaynakları başarıyla derlendi. Kullanıcının devtools izleyicisini
  etkilememek için varsayılan `build` yerine çalışma alanındaki izole çıktı
  kullanıldı. Windows geçici JAR kilidi nedeniyle Gradle test derleme görevinin
  sonlandırılması hata verdi. Java hatası olmadan üretilmiş güncel test sınıfları
  izole sınıf yoluyla çalıştırıldı. Bu sonuç normal uçtan uca Gradle derlemesinin
  sorunsuz olduğu anlamına gelmez.
- Flutter son tam paket: **2.099/2.099 geçti**. Bu sayıya 108 başlık sözleşmesi
  ve 34 başlık editörü testi dahildir. Son responsive düzeltmeden sonra tüm paket
  yeniden çalıştırıldı.
- Son Flutter statik analizinde **sorun bulunmadı**. İki depoda `git diff --check`
  temiz. `characters` mevcut 1.4.1 çözümünden doğrudan bağımlılık yapıldı, yeni
  yerel platform paketi eklenmedi.
- Üç tema, 390 dp normal yazı ve 320 dp %200 yazı için altı gerçek-font önizleme
  testi geçti. Kartlar ve editör görsel olarak incelendi.
- Telefonda yeni başlık ekleme/değiştirme/silme testi henüz yapılmadı.

Loglar çalışma alanı `.local-verification/band-member-title-tests.log` ve
frontend `build/band-member-title-*.log` dosyalarındadır. Manuel etkinlik testinin
durduğu yer `EventManualTestProgress.md` içinde korunur.
