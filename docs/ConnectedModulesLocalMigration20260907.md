# Bağlı modüller — yedekli yerel geçiş

Tamamlanma: 7 Eylül 2026, 15:18 +03:00.
Kullanıcı bu tur backend'i durdurduğunu açıkça teyit etti.

## Hedef ve ön kontrol

- Yalnız `soundconnect-local-postgres-1`, PostgreSQL 16.4,
  `soundconnectdb`, yerel 5433 portu.
- Backend 8080/8081 portlarında dinlemiyordu. Veritabanında uygulama bağlantısı
  yoktu, yalnız boşta pgAdmin bağlantısı vardı.
- Rabbit kuyruklarının tüketici, bekleyen ve işlenen mesaj sayıları sıfırdı.
- Üç geçiş henüz uygulanmamıştı. Kaynak olay kimliği tekrar eden bildirim yoktu.

## Geri yüklenerek doğrulanan yedek

Yedek repository'lerin dışında tutuluyor:

`../../.local-verification/backups/connected-modules-20260907/before.dump`

- PostgreSQL custom format, **1.424.150 bayt**.
- SHA256: `0EC7BEE2E82424FB73126FF752B30A61A0A8AF1792BC0619CC06FDC9D5764B42`.
- Ağsız, dış portsuz, geçici PostgreSQL 16.4 kopyasına `pg_restore` ile
  hatasız geri yüklendi. 84 iş tablosunun satır sayısı ve içerik özeti eşleşti.
- Aynı kopyada üç geçiş prova edildi. Sonrasında aynı 84 tablo değişmemişti.
- Geçici doğrulama container'ı ve kaynak container'daki geçici dump kopyası
  kaldırıldı. Yukarıdaki doğrulanmış kalıcı yedek korunuyor.
- Dump gerçek hesap verileri içerir, Git'e eklenmemeli veya paylaşılmamalı.

## Uygulanan geçişler

1. `scripts/db/2026-09-07-band-invitation-identity.sql`
   - `invitation_id uuid` eklendi. Bekleyen davet olmadığı için doldurulan satır 0.
   - Üyelik durumları ve unvan sürümleri değişmedi.
2. `scripts/db/2026-09-07-artist-venue-request-pages.sql`
   - Transaction dışında, beş CONCURRENTLY indeks oluşturuldu.
   - Beşi de `indisvalid=true`, `indisready=true` ve beklenen tanımda.
   - Bu dosya migration marker yazmaz, tamamlanması indekslerle doğrulandı.
3. `scripts/db/2026-09-07-notification-replay-receipts.sql`
   - Üç kolonlu teknik kayıt tablosu oluşturuldu.
   - Mevcut 25 kimlikli bildirimin içeriksiz tekrar koruma kaydı dolduruldu.
   - Kimliksiz 13 eski bildirime yapay kimlik atanmadı, içerikleri korunuyor.

İki transaction tabanlı geçişin marker kayıtları doğrulandı. Kaynak dosyalar
`ON_ERROR_STOP=1` ile ayrı ayrı çalıştırıldı, genel başlangıç/reset betiği
çalıştırılmadı.

## Son doğrulama

| Veri | Önce | Sonra |
| --- | ---: | ---: |
| Aktif grup üyeliği | 2 | 2 |
| Bildirim | 38 | 38 |
| Kimlikli bildirim | 25 | 25 |
| Etkinlik | 1 | 1 |
| Mekan bağlantısı başvurusu | 3 | 3 |
| Teknik tekrar kaydı | Yok | 25 |

- Kimliksiz bekleyen davet: 0.
- Teknik kaydı eksik veya alıcısı uyuşmayan kimlikli bildirim: 0.
- **84 mevcut iş tablosunun satır sayısı ve içerik özeti aynı.** Yalnız yeni
  `invitation_id` alanı ile migration marker/receipt tabloları karşılaştırma
  dışında. Diğer üyelik alanları, etkinlikler ve gösterim tercihleri dahil.
- Kaynak, geri yükleme, prova ve son durum fingerprint dosyaları yedekle aynı
  klasörde. Sorgular `../../.local-verification/connected-data-fingerprint.sql`
  ve `connected-migration-postcheck.sql` içinde.

Backend asistan tarafından başlatılmadı. Son kontrolde backend portları kapalı.
Kullanıcı güncel backend'i ve Flutter kodunu normal geliştirme akışıyla açmalı.
Uygulamayı veya veritabanını silmeye gerek yok. Runtime başlangıcı ve gerçek
cihaz testi bu rapor tarafından doğrulanmış sayılmaz.

Manuel durak değişmedi. B-T1, aedrum üyeliği ve gösterim tercihleri korundu.
Sıradaki adım kurucunun üye çıkarması ve yetki kaybı kontrolü.
