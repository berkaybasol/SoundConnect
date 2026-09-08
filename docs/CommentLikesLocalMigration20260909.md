# Yorum beğenileri — yedekli yerel geçiş

9 Eylül 2026. Kullanıcı bu tur backend'i durdurduğunu ve yedek alıp geçişi uygulamayı açıkça onayladı.

- Hedef: `soundconnect-local-postgres-1`, PostgreSQL16.4, `soundconnectdb`, yerel5433.
- Backend8080/8081/9090 kapalıydı. Uygulama veritabanı bağlantısı yoktu, yalnız iki boşta pgAdmin bağlantısı vardı.
- Yeni servis başlatılmadı, `.env` dosyaları değiştirilmedi, genel reset/başlangıç betiği çalıştırılmadı.

## Yedek ve prova

Kalıcı yedek iki Git deposunun dışında:

`C:/Users/user/Desktop/SoundConnect/.local-backups/comment-likes-20260909-011220/soundconnect-before-comment-likes.dump`

- PostgreSQL custom format, **1.459.199 bayt**.
- SHA256: `5694CDF362AFFE9E8D5433024BA0E931E789BA0462262F3CF30E5121C0B0B215`.
- Arşiv listesi doğrulandı, ayrı `sc_comment_likes_verify_20260909_011220` veritabanına hatasız geri yüklendi.
- **95 public tablonun** satır sayısı ve sıralı JSON içerik özeti kaynakla eşleşti.
- Yalnız yorum beğenisi geçişi bu kopyaya iki kez uygulandı. Her seferinde95 tablonun verisi aynı, kısıt/indeks doğru kaldı.
- Gerçek hedefe uygulamadan hemen önce backend/bağlantı ve veri değişmezliği yeniden doğrulandı.

## Sonuç

`scripts/db/2026-09-09-comment-likes.sql` `ON_ERROR_STOP=1` ile uygulandı. Like hedef CHECK'i COMMENT kabul edecek şekilde genişledi ve doğrulanmış durumda. `idx_like_target` bu yerel veritabanında zaten vardı, tekrar oluşturulmadı, geçerli/hazır olduğu doğrulandı. `tbl_comment` hedef sözleşmesine COMMENT eklenmedi.

95 tablonun veri özetleri önce/sonra aynı. Yorum5, beğeni0, etkinlik7 olarak korundu. Geçiş örnek yorum/beğeni yazmadı, geçmiş etkinlik silmedi.

Yalnız bu çalıştırmada oluşturulan geçici doğrulama veritabanı kaldırıldı; yokluğu son sorguyla doğrulandı. Kalıcı yedek ve kaynak konteynerdeki geçici dump kopyası duruyor. Yedek gerçek hesap verisi içerir, Git'e eklenmemeli veya paylaşılmamalı.

Yerel yürütme betiği: `../.local-verification/apply-comment-likes.ps1` (SoundConnect ortak çalışma köküne göre `.local-verification/` altında). Backend kapalı bırakıldı. Kullanıcı güncel backend'i başlatıp Flutter'a hot restart yapmalı. Gerçek cihaz akışı bu geçiş raporuyla geçmiş sayılmaz.
