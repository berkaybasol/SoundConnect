# Oturum ve aşama devir şablonu

Bu şablon yeni bir yönlendirme zinciri oluşturmak için değil, aktif aşamanın
CALISMA/kabul/teslim kaydını eksiksiz tutmak içindir. Tek giriş BASLA, tek güncel
durum DURUM'dur. Devir oluşturulması işi tamamlanmış saymaz.

## Her oturum sonunda kaydedilecekler

| Alan | Gereken bilgi |
| --- | --- |
| Görev/yetki | Aşama, kapsam, son doğrudan kullanıcı kararı, sınırlar |
| Kaynak | Repo, branch, HEAD, staged/unstaged/untracked değişikliklerin sahibi |
| Değişiklik | Neden, değişen dosyalar, kullanılan mevcut altyapı, bağlı migration |
| Bulgular | Açık/kapalı bulgu, öncelik, gerçek kanıt; varsayım ayrı |
| Otomatik | Komut, sürüm/ortam, sonuç, failure/skip nedeni; örtüşen sayıları toplama |
| Build | Kaynak ilişkisi, JAR/APK/AAB kimlik/hash, imza/paket/flag; eski çıktıyı yeni sayma |
| Gerçek kabul | API/DB/realtime, fiziksel cihaz, kullanıcı görsel onayı, dış teslim ayrı |
| Koruma | Eski veri/oturum/cihaz/servis/evidence ne durumda bırakıldı? |
| Dış engel | Tam eksik erişim/karar, biten bağımsız işler, kalan tek somut ihtiyaç |
| Git yayını | Commit/PR/CI/merge SHA/uzak eşlik veya henüz yapılmadı |
| Devam | Tam sonraki adım, okunacak dar kaynaklar; gereksiz genel tekrar yok |

## Güncelleme sırası

1. Asıl aşama çalışma/kabul/teslim kanıtını yaz.
2. DURUM'da tek aktif aşama, gerçek branch/commit, kabul ve açık işi güncelle.
3. BASLA'nın kısa girişini ve aktif görev linkini güncelle.
4. Yeni karar için PLAN + kök `hafiza/kararlar.md`; durum özeti için ilgili
   `hafiza/isler.md` ve `hafiza/README.md` bölümünü güncelle. Kopya uzun günlük yok.
5. Aşama kapanıyorsa bağımsız inceleme/kabul ve Git kapanışının ayrı sonuçlarını
   kaydet; ardından sonraki branch/görev girişini hazırla. Ürün işine otomatik geçme.

MD'lere secret/token/parola, özel key, tam kişisel veri veya hassas env içeriği
koyma. Değeri yerine gerekli değişken adı/varlık/kanıt konumunu güvenli biçimde
kaydet. Linkleri taşınabilir tut; dış workspace kanıtının bağımlılığını belirt.

Her aşamanın uygulama görevinde ayrı **Manuel kabul** bölümü zorunludur.
YAPILMADI, ENGELLİ, BAŞARISIZ, GEÇTİ ve gerekçeli UYGULANAMAZ ayrı durumlar;
herhangi biri diğerinin yerine kullanılamaz. Kullanıcı onayı uydurulamaz.
