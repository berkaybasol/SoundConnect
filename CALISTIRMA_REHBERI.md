# SoundConnect Yerel Çalıştırma Rehberi

Bu rehber, bilgisayarı yeniden başlattıktan sonra SoundConnect'i nasıl açacağını ve hangi programda hangi düğmeye basacağını anlatır.

## En kısa cevap

Sadece uygulamayı test edeceksen:

1. Docker Desktop'ı aç ve tamamen başlamasını bekle.
2. PowerShell'i `SoundConnect-Backend` klasöründe aç.
3. Şunu çalıştır:

   ```powershell
   .\dev.cmd up
   ```

4. Backend, media worker ve üç altyapı servisinin `healthy` olduğunu gördükten sonra Android Studio'yu aç.
5. Flutter uygulamasında yeşil Run düğmesine bas.
6. IntelliJ'de backend için yeşil Run düğmesine basma. Backend zaten Docker içinde çalışmaktadır.

Bu, normal test sırasında kullanacağın ana çalışma şeklidir.

İlk çalıştırmada `dev.cmd`, medya worker için iki ayrı ve güçlü yerel parola üretir:

- `.env.worker-db.local`
- `.env.worker-rabbit.local`

Bu dosyaları elle doldurman gerekmez; Git'e girmezler ve API sürecine verilmezler. PostgreSQL, Redis ve RabbitMQ portları da yalnızca bu bilgisayardan erişilebilecek şekilde `localhost` üzerinde açılır.

## Hangi parça nerede çalışıyor?

| Parça | Nasıl çalışır? | Adres |
|---|---|---|
| Backend API | Docker Compose | `http://localhost:8080` |
| Flutter frontend | Android Studio'da yeşil Run | Telefon/emülatör |
| PostgreSQL | Docker Compose | `localhost:5433` |
| Redis | Docker Compose | `localhost:6379` |
| RabbitMQ | Docker Compose | `localhost:5673` |
| RabbitMQ paneli | Tarayıcı | `http://localhost:15672` |
| Swagger | Tarayıcı | `http://localhost:8080/swagger-ui.html` |

## Bilgisayarı yeniden başlattım

Her seferinde şu sırayı uygula:

```text
Docker Desktop'ı aç
        ↓
SoundConnect-Backend klasöründe .\dev.cmd up
        ↓
Servislerin healthy olmasını bekle
        ↓
Android Studio'da Flutter için yeşil Run
```

`dev.cmd up` tekrar tekrar çalıştırılabilir. Çalışan servisleri bozmaz; eksik veya değişmiş imajları günceller.

## Backend kodu geliştireceğim veya debug edeceğim

Backend'e breakpoint koymak ve IntelliJ debugger kullanmak istediğinde:

1. PowerShell'i `SoundConnect-Backend` klasöründe aç.
2. Şunu çalıştır:

   ```powershell
   .\dev.cmd idea
   ```

3. Komut PostgreSQL, Redis ve RabbitMQ'yu çalıştırır; Docker backend'ini durdurur.
4. IntelliJ'de `SoundConnectApplication` için yeşil Run veya Debug düğmesine bas.
5. Flutter için Android Studio'daki yeşil Run düğmesine bas.

IntelliJ backend'i `.env.local` dosyasını otomatik yükler. IntelliJ Run Configuration içine env değerlerini yeniden yazman gerekmez.

Docker backend moduna dönmek için önce IntelliJ'deki backend'i kırmızı Stop düğmesiyle durdur, ardından:

```powershell
.\dev.cmd up
```

IntelliJ backend'i çalışırken `dev.cmd up` kullanırsan iki backend de `8080` portunu isteyeceği için port çakışması yaşarsın.

## Flutter frontend'i nasıl çalıştıracağım?

Flutter her zaman Android Studio üzerinden çalışır:

1. Telefonu veya emülatörü bağla.
2. Doğru cihazı seç.
3. Yeşil Run düğmesine bas.

Backend Docker'da veya IntelliJ'de olabilir; ikisi de telefona `8080` üzerinden aynı API'yi sunar. Fiziksel Android cihaz localhost'a ulaşamıyorsa daha önce kullandığımız ADB reverse bağlantısını kontrol et:

```powershell
adb reverse tcp:8080 tcp:8080
```

## PgAdmin ile Docker veritabanına bağlanma

Eski Windows PostgreSQL ile Docker PostgreSQL farklı sunuculardır:

| PostgreSQL | Host | Port |
|---|---|---:|
| Eski Windows kurulumu | `localhost` | `5432` |
| SoundConnect Docker | `localhost` | `5433` |

PgAdmin'de yeni bir server connection oluştur:

- Name: `SoundConnect Docker Local`
- Host: `localhost`
- Port: `5433`
- Maintenance database: `soundconnectdb`
- Username: `.env.local` içindeki `SOUNDCONNECT_POSTGRES_USERNAME`
- Password: `.env.local` içindeki `SOUNDCONNECT_POSTGRES_PASSWORD`

PgAdmin bağlantın `5432` portundaysa eski Windows veritabanına bakıyorsundur. Docker backend'in kullandığı veriler orada görünmez.

## Yerel verileri tamamen sıfırlama

Üyelikleri ve bütün yerel test durumunu temiz bir başlangıca döndürmek için PgAdmin'den Force Drop yapma. Şunu kullan:

```powershell
.\dev.cmd reset-local -Force
```

Bu komut yalnızca Docker'daki yerel geliştirme verilerini kalıcı olarak siler:

- PostgreSQL veritabanı ve üyelikler
- Redis cache, OTP ve rate-limit verileri
- RabbitMQ kuyrukları

Ardından bütün stack'i yeniden oluşturup başlatır. `.env.local`, kaynak kod, S3 dosyaları ve eski Windows PostgreSQL etkilenmez.

Reset akışı, Hibernate temiz temel şemayı oluşturduktan sonra Studio şemasını da otomatik olarak senkronize eder. Ayrı bir SQL veya migration komutu çalıştırman gerekmez. IntelliJ için kullanılan `dev.cmd idea` komutu da mevcut yerel şemayı idempotent biçimde hazırlar.

## Durum ve loglar

Servislerin durumunu göster:

```powershell
.\dev.cmd ps
```

Backend loglarını canlı izle:

```powershell
.\dev.cmd logs -Service backend
```

Tüm servislerin loglarını izle:

```powershell
.\dev.cmd logs
```

Log ekranından çıkmak için `Ctrl+C` kullan.

## Sistemi kapatma

```powershell
.\dev.cmd down
```

Bu komut konteynerleri kapatır fakat PostgreSQL, Redis ve RabbitMQ verilerini silmez. Daha sonra `dev.cmd up` dediğinde kaldığın yerden devam edersin.

## Komut özeti

| İhtiyaç | Komut |
|---|---|
| Normal test modu | `.\dev.cmd up` |
| IntelliJ backend geliştirme modu | `.\dev.cmd idea` |
| Yalnızca altyapıyı başlat | `.\dev.cmd infra` |
| Servis durumları | `.\dev.cmd ps` |
| Backend logları | `.\dev.cmd logs -Service backend` |
| Her şeyi kapat | `.\dev.cmd down` |
| Tüm yerel test verisini sil ve yeniden başlat | `.\dev.cmd reset-local -Force` |
| Compose yapılandırmasını doğrula | `.\dev.cmd config` |

## Sık karşılaşılan durumlar

### `Port 8080 already in use`

Docker backend ve IntelliJ backend aynı anda çalışıyordur. Birini durdur:

- Normal test için IntelliJ backend'i durdur ve `dev.cmd up` kullan.
- Debug için `dev.cmd idea` kullanıp ardından IntelliJ backend'i başlat.

### Backend çalışıyor ama Flutter bağlanamıyor

Önce kontrol et:

```powershell
.\dev.cmd ps
```

Backend `healthy` görünmelidir. Fiziksel Android cihaz kullanıyorsan `adb reverse tcp:8080 tcp:8080` bağlantısını yeniden kur.

### Veritabanını PgAdmin'de göremiyorum

Bağlantının `localhost:5433` olduğundan emin ol. `5432`, eski Windows PostgreSQL'dir.

### Env değiştirdim

`.env.local` dosyasını kaydettikten sonra Docker modunda:

```powershell
.\dev.cmd up
```

IntelliJ modunda backend'i durdurup tekrar yeşil Run/Debug düğmesiyle başlat.
