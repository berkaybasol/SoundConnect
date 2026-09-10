# Dinleyici hesabını kalıcı silme

`DELETE /api/v1/users/me/account`, kimliği doğrulanmış dinleyiciye aittir. Gövde `confirmation: "DELETE"` ile LOCAL hesapta `currentPassword`, GOOGLE hesapta doğrulanabilir `googleIdToken` içerir. Token doğrulaması veritabanı işlemi açılmadan yapılır; şifre veya Google subject, kullanıcı satırı kilitlendikten sonra güncel kimlikle karşılaştırılır. Giriş limitlerinden ayrı hesap silme kotası uygulanır. İstek/sır bilgileri loglanmaz.

Başarı `200 / success:true / data:true` döndürür. Yanlış yeniden doğrulama `1006/403`, farklı profil veya yönetici rolü `1007/409`, kalıcı silinmiş hesap `1008/410` üretir. İstek yalnızca mevcut oturumun hesabını hedefler. Yönetici silme yolu da dinleyici için aynı temizliği kullanır. Profesyonel hesapların kapatılması bu akışa dahil değildir. Bekleme veya geri alma dönemi yoktur.

Tek veritabanı işlemi şu değişiklikleri birlikte tamamlar:

- Dinleyici profili, Spotify listeleri, profil medyası bağlantıları, Overthinking yazıları ve bu yazılara bağlı yorum/beğeni/paylaşım/görüntüleme istekleri kaldırılır.
- Kullanıcının etkinlik planı/paylaşımı, beğenileri, takipleri, başvuruları ve kaydedilmiş ilanları temizlenir. Başkalarının yazılarındaki kendi yorumunun metni kaldırılır; başkalarının yanıtları korunur.
- Sahibi olduğu masalar kapanır; diğer masalardaki katılımı ve oyun kimlik görüntüleri temizlenir. Kendi masa profil paylaşımları ve sahip olduğu masaların başkalarınca yapılmış paylaşımları, bunlara bağlı yorum ve beğenilerle birlikte kaldırılır. Stüdyo rezervasyonu iptal edilir, doluluk serbest bırakılır ve telefon görüntüsü silinir.
- Bildirim kutusu ve gönderim kuyruğundaki kimlik görüntüleri temizlenir. Eski bildirim tekrarını engelleyen teslim makbuzları kalır. Teslim anında hesap ve Overthinking kaynak kaydı yeniden doğrulanır.
- Kullanıcı adı, e-posta, parola, sağlayıcı kimliği, telefon, konum, avatar ve profil metinleri değiştirilemez teknik bir kayda dönüştürülür. Hesap `INACTIVE` olur; roller ve izinler kaldırılır. Eski JWT ile HTTP ve WebSocket yetkilendirmesi yapılamaz.

Karşı taraftaki özel mesaj geçmişi korunur. Ekranda “Silinmiş hesap” ve boş avatar görünür; yeni mesaj gönderilemez. Teknik UUID ve mevcut şemanın değiştirilemez `public_code` değeri kalır; açık profil erişimi silinmiş/pasif hesap denetimiyle kaldırılır. Bu, anonimleştirilmiş bütün verinin fiziksel olarak yok edildiği iddiası değildir. Kullanıcıya silme diyaloğunda mesaj geçmişinin kalacağı açıklanır.

Medya satırları aynı işlemde `DELETION_PENDING` olur. Dosyalar, mevcut yükleme/transcode yetki sürelerine ve silme işçisinin güvenli bekleme sınırlarına uyarak arka planda kaldırılır. Dosya silme başarısızsa kalıcı niyet ve tekrar deneme korunur. Yedekler ve daha önce istemcilere ulaşmış kopyalar bu veritabanı işlemiyle silinmiş sayılmaz; yayın ortamının yedek saklama ve medya/CDN silme doğrulaması ayrıca yapılmalıdır.

Veritabanı geçişleri sıralıdır: `2026-09-10-overthinking-inbox-seen.sql`, `2026-09-10-overthinking-profile-shares.sql`, `2026-09-10-overthinking-production-safety.sql`, `2026-09-10-listener-account-erasure.sql`, `2026-09-10-tablegroup-profile-shares.sql`, `2026-09-10-tablegroup-profile-share-history.sql`. Yerel `scripts/dev.ps1` bu dosyaları başlangıç sırasında uygular. Üretimde uygulama trafiği açılmadan aynı sıra uygulanmalıdır; Hibernate sütun eklemesi tetikleyici ve yabancı anahtarların yerini tutmaz.

Masa süresi dolduktan sonra katılımcı hesabı siliniyorsa, kalan profil paylaşımlarındaki son kişi sayısı katılımcı temizliğinden önce sabitlenir. Bu görüntü yalnızca anonim toplam sayıyı ve mevcut herkese açık masa alanlarını taşır; katılımcı kimlikleri veya sohbet saklanmaz. Silinen hesabın kendi paylaşımları ve sahibi olduğu masaların tüm paylaşımları, sabitlenmiş görüntüleri de dahil olmak üzere kaldırılır.

Silinen kullanıcıya yeni referanslar eklemeyi engelleyen tetikleyiciler, hesap silmeyle eşzamanlı eski istekleri kullanıcı satırı kilidiyle sıralar. Değişmeyen mevcut referans güncellemeleri (ör. mesaj okundu işareti) engellenmez. Silinmiş kullanıcı kimliği tekrar etkinleştirilemez.

Silme işlemi kullanıcı satırından sonra masa/oyun ve rezervasyon kayıtlarını, ardından dinleyici profilini kilitler. Böylece devam eden oyunların profil okuması ile karşılıklı kilit bekleme oluşmaz. Hesap silmeye ait oyun geçişleri işlem içinde isim/avatar çözümlemez; başarılı commit callback’i yalnızca oyun UUID’sini sınırlı arka plan kuyruğuna bırakır. Güncel veri işçinin ayrı veritabanı işleminde okunur; eski işlem bağlantısını bırakmadan ikinci bağlantı beklemez. Kuyruk dolu veya kapanıyor olsa da veritabanındaki kapanış ve katılımcı temizliği korunur; anlık bildirim atlanabilir. Overthinking görüntüleme istekleri de kaynak/istek kilidinden önce diğer katılımcının kullanıcı satırını kilitleyerek hesap silmeyle aynı sırayı izler.

Mobil uygulamanın mevcut giriş akışı LOCAL olduğundan şifre doğrulamalı silme diyaloğu uygulanmıştır. Backend Google tokenını destekler; mobil Google giriş/yeniden doğrulama SDK entegrasyonu mevcut değildir ve bu çalışma onu tamamlanmış saymaz.
