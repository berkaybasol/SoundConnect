# SoundConnect — yeni oturum başlangıcı

Son güncelleme: **8 Ekim 2026**. Bu paket, kullanıcının onayladığı sekiz aşamalı
üretime hazırlık çalışmasının tek güncel girişidir. Kullanıcı her oturumda
geçmişi yeniden anlatmak istemiyor. İlgili bağlamı sen bu kayıtlardan edin.

## Şimdi nerede kaldık?

- **Aşama 01: KABUL ONAYLI; GIT/CI KAPANIŞI SÜRÜYOR.** İki repoda `ready-for-prod-01` açık; başlangıç
  HEAD'leri taze doğrulandı, önceki hazırlık belgeleri korundu.
- 8 Ekim yeni oturumunda kullanıcı uygulamayı başlattı. Bu oturumun mobil kabulü
  açık kullanıcı kararıyla Android Studio emülatöründe; fiziksel Vivo zorunlu
  değil. Emülatör kanıtı fiziksel kabul sayılmaz, insan görsel onayı ayrıdır.
- Kullanıcı yeni oturumda bu dosyadan devam etmeni istediğinde **01'in mevcut
  yetkili kapsamını uygula**; sekiz aşamanın tamamını birden başlatma.
- 01 kapsamı: şifre sıfırlama sonrası eski oturumlar, Redis kesintisinde giriş
  deneme koruması ve sağlık/uyarı v1. Ayrıntı [01 görevi](asamalar/01/GOREV.md).
  Teknik kabulü baştan başlatma; kullanıcı sonAPK/gösterilenakışları onayladı.
  Somut sonraki adım yetkili Git/CI kapanışını tamamlamaktır. Son kullanıcı
  talimatıyla bunun ardından02öncesi kapsamlı observability ve PC kapalıyken
  alarm için dış izleme/barındırma/maliyet konuşulacak;02geçişi henüz yapılmaz.
- Son kanıt: iki güvenlik düzeltmesi ve bağlı admin parola yolu testleri geçti;
  izole gerçek HTTP/STOMP kabulü geçti. Sağlık/mobil ekranı uygulandı, APK
  emülatöre verileri korunarak yüklendi. İki TEST e-posta iki alıcıya teslim
  edildi. Startup sırasında bulunan lifecycle sorunu düzeltildi; son runtime
  healthy ve hash eşliği doğrulandı. Mobil deneme raporu gerçek DB ve UI'da
  görüldü. Final03APK ile rapor/eski ölçüm/toparlanma ve reset sonrası eski
  oturumun reddi/yeni parola ile giriş geçti. Son tam backend6056PASS/0hata;
  mevcut101skip+18parametreşablonu ayrı, aşamayla ilgili454testte0skip.
  Asıl emülatör admin oturumuna dönüldü; mevcut veriler korundu. Kullanıcı
  doğrudan kabul verdi; mevcut commit/push/PR/CI/normalmerge yetkisi uygulanıyor.
  FE e33ffd17/PR5/CI başladı; BE yayın hazırlanıyor. Gerçek sonuçları DURUM'dan
  doğrula; tekrar izin sorma.02branch geçişi yukarıdaki konuşmadan sonraya kaldı. Tüm PC kesintisine
  dayanıklı dış host kabulü yoktur. Ayrıntı ve sonraki adım DURUM/CALISMA'dadır.

## Kısa okuma sırası

1. Çalışma alanı ve ilgili reponun `AGENTS.md` dosyaları.
2. [DURUM.md](DURUM.md): aktif aşama, branch/commit, gerçekleşenler ve açıklar.
3. [PLAN.md](PLAN.md): onaylı sıra, Git kapanışı ve yetki sınırları.
4. DURUM'da gösterilen aktif görev ve çalışma kaydı; şu an
   [01/GOREV.md](asamalar/01/GOREV.md) ve [01/CALISMA.md](asamalar/01/CALISMA.md).
5. Yalnız ihtiyaç duyulan bulgular için [kaynak incelemesi](INCELEME-20261008.md)
   ve oradan ilgili gerçek kod. Tarihsel günlüklerin tamamını baştan okuma.

Bu repo, `C:\Users\user\Desktop\SoundConnect` içindeki iki ayrı Git reposundan
biridir. Frontend kardeş `SoundConnect-Frontend`, backend `SoundConnect-Backend`.
Üst klasör Git reposu değildir. Komutları doğru repoda çalıştır.

## Devralırken doğrula

Her iki reponun HEAD, branch, staged/unstaged/untracked durumunu taze oku.
Başlangıç SHA'larını DURUM ile karşılaştır. Hazırlığın AGENTS/MD belgeleri ve
devam oturumunun Aşama01 kaynak/test değişiklikleri
bilinçli ve henüz commit edilmemiştir; silme veya ilgisiz dirty iş sayma.
Fark varsa kaynağını belirle, kullanıcı işini koru. Çalışan servis/cihazın eski
kayıttaki durumda kaldığını varsayma; gerekli dar kontrolleri yap.

## Her oturumun sorumluluğu

Aktif aşamanın somut çalışma kaydını ilerledikçe güncelle. Oturum biterken önce
o kayıt ve kabul kanıtlarını, sonra DURUM'u, son olarak bu dosyanın “Şimdi nerede
kaldık?” bölümünü güncelle. Yeni karar varsa PLAN ve proje `hafiza` özetine işle.
Kullanıcıya kopyalatılan uzun ve farklı devir promptları üretme; bu giriş sabit
kalsın. [Devir şablonundaki](DEVIR_SABLONU.md) bilgileri mevcut kayda yerleştir.

Kabulü, branch'i, CI'ı veya dış erişimi varsayarak tamamlandı işaretleme.
Belgeler arasında çelişki varsa en yeni açık kullanıcı talimatını koru ve farkı
kaydet. Liste, sıradaki aşamayı kendiliğinden geliştirme yetkisi değildir.

## Yeni oturum için tek mesaj

> SoundConnect-Backend/docs/ready-for-prod/BASLA.md dosyasını oku. Güncel durumu
> doğrula ve aktif aşamanın yetkili kapsamından devam et. Devir kayıtlarını
> çalışma boyunca güncel tut.
