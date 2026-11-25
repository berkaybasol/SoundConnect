# Hesabı kalıcı silme

`DELETE /api/v1/users/me/account` dinleyici, müzisyen, mekân ve stüdyo hesaplarını kapsar. İstek `confirmation: "DELETE"` ile LOCAL hesapta `currentPassword`, GOOGLE hesapta `googleIdToken` içerir. Google kimlik doğrulaması veritabanı işleminden önce yapılır. Sunucu kullanıcı satırını kilitler ve güncel şifreyi veya Google kimliğini karşılaştırır. Hesap silme denemelerine kota uygulanır.

Başarı yanıtı `200 / success:true / data:true` olur. Kimlik uyuşmazlığı `1006/403`, yönetim yetkileri veya uyumsuz profil türleri `1007/409`, silinmiş hesap `1008/410` döndürür. İşlem kalıcıdır.

Son kurucu için `GET /api/v1/user/bands/account-deletion-options` bandları ve uygun aktif üyeleri listeler. Silme isteğinin `bandDecisions` dizisinde her band için `TRANSFER` ve `successorUserId` ya da `CLOSE` seçilir. Devir, kurucunun silme onayıyla aynı işlemde geçer. Band üyeliği veya karar geçerliliği değişirse sunucu `9223/409` döndürür ve işlem geri alınır.

Uygulamaya erişemeyen kullanıcı `/api/v1/public/account-deletion/index.html` adresinde kayıtlı e-postasına kod isteyebilir. E-postası doğrulanmış yerel hesaplar mevcut SoundConnect şifresiyle de ilerleyebilir. Her iki yol hesap adını ve varsa devir seçeneklerini gösterir. Kullanıcının son onayına kadar hesap açık kalır. Doğrulama yalnızca hesap silmeye yetki verir ve on dakika geçerlidir.

Veritabanı bu değişiklikleri birlikte tamamlar.

- Dinleyici profili, Spotify listeleri, profil medyası bağlantıları, Overthinking yazıları ve bu yazılara bağlı yorum/beğeni/paylaşım/görüntüleme istekleri kaldırılır.
- Hesapta geçmişten kalan Pazar ilanları ve kaydedilen Pazar ilanı bağlantıları kaldırılır. Mevcut bir şikâyetteki ilan görüntüsü ve kanıt fotoğrafları yönetici incelemesi için saklanır. Diğer ilan fotoğrafları arka planda temizlenir. Başka bir satıcının ilanı yayında kalır.
- Hesabın kişisel veya band adına yayımladığı açık Collab ilanları kapanır. Bu ilanların bekleyen başvurularında kapanış görünür. Hesabın başka ilanlara yaptığı bekleyen başvurular geri çekilir, taraf olduğu açık işler iptal edilir ve başvurularındaki telefon temizlenir. Aynı bandın başka üyesine ait ilanlar ve tamamlanmış işlerin durumu korunur.
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

Android’de dinleyici, hesabı için mevcut şifresini girer veya Google kimliğini yeniden doğrular. Sunucu hangi yöntemin geçerli olduğunu hesap kaydından belirler. Google hesabı için doğrulanmış kimlikteki kalıcı hesap tanımlayıcısının (`subject`) kayıtla eşleşmesi gerekir. Geçersiz Google doğrulaması `1006/403`, Google doğrulama hizmetinin geçici kesintisi `1117/503` döndürür.

Kendi hesabını silme, etkin ve e-postası doğrulanmış dinleyiciler içindir. Dinleyicinin yanında teknik `ROLE_USER` rolü bulunabilir. İşlem kilit altında güncel askıyı, rolleri, gerçek profil kayıtlarını ve doğrudan ya da rolden gelen yönetim izinlerini denetler. `ADMIN_PANEL_ACCESS`, `READ_USERS` veya `MANAGE_` ile başlayan bir izin varsa `1007/409` döner. Olağan bir ek izin (örneğin `READ_LOCATION`) dinleyicinin hesabını silmesine engel olmaz.
