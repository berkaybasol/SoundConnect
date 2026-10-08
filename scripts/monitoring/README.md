# Bağımsız sağlık izleyicisi

`health_watch.py` mevcut yetkili `/api/v1/admin/system-health` ölçümünü okur.
Sunucunun kendi kuyruğuna, e-posta worker'ına veya Redis'ine gönderim için bağlı
değildir; mevcut MailerSend sağlayıcısına doğrudan HTTPS ile bağlanır. Yeni hesap,
abonelik, IAM/RBAC veya üretim dağıtımı oluşturmaz.

İzleyici **ayrı bir makinede** çalıştırılırsa uygulama hostunun kesintisini
görebilir. Aynı bilgisayardaki koşu yalnız API/işlem kesintisini gösterebilir;
bilgisayarın kapanması, yerel ağ/elektrik kesintisi için harici kabul değildir.
8 Ekim ortamı kullanıcıya göre yalnız yereldir; bu sınır ayrıca kaydedilir.

## Yapılandırma

Python 3.11+ ve standart kütüphane yeterlidir. Değerleri mevcut secret yönetimi
üzerinden işlem ortamına ver; komut satırına, loga veya repoya yazma:

- `SOUNDCONNECT_MONITOR_URL`: admin sağlık endpointinin HTTPS adresi.
- `SOUNDCONNECT_MONITOR_TOKEN`: mevcut `ADMIN_PANEL_ACCESS` yetkisine sahip geçerli
  JWT. Süresi ve yenilemesi işletim sorumluluğudur. Reddi `MONITORING_LOST`
  uyarısıdır; uygulama kesintisi diye sunulmaz. Ayrı geniş yönetici hesabı oluşturma.
- `SOUNDCONNECT_MONITOR_INTERVAL_SECONDS`: 15–300, varsayılan 30.
- `MONITOR_MAIL_API_KEY`, `MONITOR_MAIL_FROM`: mevcut doğrulanmış e-posta sağlayıcısı.
- `MONITOR_MAIL_TO`: kullanıcı tarafından açıkça seçilmiş bir veya iki alıcı,
  virgülle ayrılır. Test alıcıları yerel Git dışı ayarda tutulur.
- `SOUNDCONNECT_MONITOR_TEST=true`: konuya açık `[TEST]` işareti ekler.

`python scripts/monitoring/health_watch.py --state /private/health-state.json`
komutu sürekli çalışır. `--once` tek gözlem yapar. Yalnız kontrollü localhost
kabulünde `--loopback-test` HTTP'ye izin verir; uzak HTTP kabul edilmez.
Yönlendirmeler izlenmez, credential URL parametresi yapılmaz, yanıt en fazla64KB.

## Eşikler ve anlam

Üç ardışık aynı sorun gözlemi ilk alarmı üretir (varsayılan aralıkta yaklaşık
60–90sn algılama). İki normal ölçüm toparlanmayı bildirir. İlk normal ölçüm
sessizdir; aynı hata parmak izi restart sonrasında da tekrar gönderilmez.
Yeni önemli durum ayrı parmak izi üretir. Gönderim başarısızsa sonraki gözlemde
yeniden denenir; bu sınırlı at-least-once davranışıdır. Sağlayıcı yanıtı kaybolursa
yinelenen e-posta ihtimali vardır. Atomik yerel durum dosyası taşınırsa aynı
izlenen hedef ve alıcılarla birlikte taşınmalıdır; çoklu izleyici için ortak
dedup yoktur. Şema2 durum dosyası hedef URL, gönderici, alıcı kümesi ve test
modunun SHA-256 bağına sahiptir; bunların açık değerlerini veya credential'ı
saklamaz. Credential yenilemesi tekrar engellemeyi korur. Başka hedef/alıcı/test
modu, eski şema veya bozuk dosya `CONFIGURATION_REQUIRED` ile durur; yeni hedef
için ayrı özel durum dosyası kullanılır. İzleyici eski dosyayı silmez veya
sessizce yeniden yorumlamaz. İki örnekleme aralığından uzun kesinti veya geriye
saat sıçraması ardışık ölçüm sayısını sıfırlar; bildirilmiş olay korunur.

HTTP200 yeterli değildir: gövde, component durumu, ölçüm yaşı ve snapshot
zamanı kontrol edilir. UP ancak ölçüm zamanı, tam saniye yaşı ve HEALTHY nedeni
tutarlıysa sağlıklıdır; metadata eksikliği UNKNOWN olarak alarm adayına girer.
UNKNOWN/STALE/DEGRADED alarm adayıdır, DISABLED hata sayılmaz. Tek dar istisna
`api / UNKNOWN / NO_TRAFFIC` ölçümüdür: measuredAt/age tutarlı ve taze, metrikler
tam olarak `requestCount=0` ile sonlu `0 < windowSeconds <= staleAfterSeconds`
olmalıdır. Bu, ölçülmüş boş bir trafik aralığıdır; normal idle → trafik → idle
döngüsü alarm üretmez. Metadata eksikliği, eski ölçüm, WARMING_UP, PROBE_FAILED
ve başka bileşendeki UNKNOWN bu istisnaya girmez. Genel UNKNOWN yalnız bu boş
API aralığından kaynaklanıyorsa alarm sınıflaması UP olur. Başka bileşenin
UNKNOWN/STALE/DEGRADED/DOWN durumu korunur; genel DOWN da sessizleştirilmez.

Bu alarm sınıflamasındaki UP, işlem gerektiren sinyal bulunmadığını belirtir.
İki böyle gözlem mevcut alarmın artık gözlenmediğini bildirebilir; boş trafikte
API gecikmesi/hata oranının iyileştiğini kanıtlamaz. Backend ve yönetici UI
ölçümü UNKNOWN/NO_TRAFFIC olarak tutar; latency/error ölçümü veya kapasite
iddiası üretilmez.

Mail içeriği yalnız sabit durum kodları, zaman ve olay parmak izidir;
hata metni, token, URL, hesap veya ürün içeriği yoktur. `PROVIDER_ACCEPTED`
yalnız202 yanıtını belirtir; posta kutusu teslimi ve kullanıcı okuması ayrıdır.
`send_mail` varsa sınırlı `x-message-id` sağlayıcı kimliğini yalnız çağırana
döndürür; ana izleyici bunu loga/durum dosyasına yazmaz. Yetkili kabul aracı özel
kanıtında bu kimlikle teslim durumunu ayrıca sorgulayabilir. Eksik kimlik kabul
edilmiş isteği yeniden göndertmez. Sağlayıcının [resmî sözleşmesi](https://developers.mailersend.com/api/v1/email)
202 yanıtını asenkron kabul olarak tanımlar.

İzleyicinin kendisinin durmasını fark etmek için bağımsız host/scheduler ve
harici dead-man kontrolü gerekir. Bunlar yerel betiğin veya mevcut kanıtın
sağladığı garanti değildir. Kapasite/SLA vaadi yoktur.

## Doğrulama

`python -m unittest discover -s scripts/monitoring -p 'test_*.py' -v`
durum/eskime, tekrar engelleme, restart/toparlanma, credential ve payload
mahremiyet sınırlarını doğrular. Gerçek API kesintisi ve iki deneme e-postası
ayrı kabul kaydına yazılır. Ortak Redis/Rabbit/DB arıza testi için durdurulmaz.
