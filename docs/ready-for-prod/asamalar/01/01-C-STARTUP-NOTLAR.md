# 01-C bağlı API başlangıç düzeltmesi

8 Ekim 2026. Sağlık paneli değişiminin ilk yerel JAR rollout'unda bulunan
başlangıç kilitlenmesi; runtime sahibi root, dar kod düzeltmesi auth_revocation.
Bu kayıt yeni JAR'ın gerçek ortamda açıldığı iddiası değildir; son runtime
doğrulaması root rollout kaydında ayrıca tamamlanacaktır.

## Bulgular ve kök neden

İlk `b3a59315...` JAR denemesinde `SystemHealthService.@PostConstruct` uygulama
singleton'ları henüz oluşturulurken sıfır gecikmeli sağlık görevlerini başlattı.
Probe'lar salt okunur ölçüm yaparken Spring `ObjectProvider` bean'lerine ve ilk
Rabbit bağlantısına erişiyordu. İlk bağlantı RabbitAdmin'in declaration
listener'ını çalıştırdı; bu listener `getBeansOfType` ile aynı bean factory'ye
geri girdi. Böylece gözlem görevi uygulamanın kurulmasına rakip oldu.

Özel thread dump'ın yalnız ilgili stack bölümleri okundu:
`tmp/ready-for-prod-01/private/startup-attempt01-thread-dump.log`.
Credential/ortam içeriği veya tüm dump bu belgeye kopyalanmadı. Görülen zincir:

- `main #1`: `DefaultSingletonBeanRegistry.getSingleton:313` condition wait;
  üst zincirde `AbstractBeanFactory` ve `finishBeanFactoryInitialization`.
- `system-health-probe #36/#37/#38`: aynı RabbitAdmin lock'unda bekleyen
  `RabbitAdmin.getQueueInfo:467`.
- `system-health-probe #39`: `AbstractBeanFactory.getObjectForBeanInstance`
  ve `getSingleton:346` beklemesi; çağıran zincir
  `RabbitAdmin.processDeclarables:760 -> redeclareBeanDeclarables:674 ->
  initialize:651 -> onCreate`, ardından gerçek health contributor/probe.

Root'un 06:19:44 UTC startup log gözlemi sağlık thread'inin `amqpAdmin` ve
repository oluşturmasına, main thread'in başka repository/service/controller
singleton'ları üzerinde çalışmasına işaret etti. Yaklaşık üç dakika ilerleme
olmaması ve düşük CPU ile birlikte bu kayıtlar startup sırasında bean creation
ile RabbitAdmin declaration arasındaki kilit beklemesini gösteriyor. Sağlık
probe timeout'u yalnız future'ı iptal ediyor; Spring/Rabbit kilit beklemesi
interrupt'ı hemen teslim etmek zorunda olmadığından timeout başlangıcı kurtarmadı.

`MobileDiagnosticsStore` retention görevi de `@PostConstruct` ile başlıyordu.
60 saniyelik gecikmesi uzun başlangıçta bu riski ortadan kaldırmaz; aynı yaşam
döngüsü sınırına taşındı. Bunun thread dump'taki kilitlenmeyi fiilen başlatan
görev olduğu iddia edilmiyor.

## Düzeltme ve doğrulama

İki görev `ApplicationReadyEvent` sonrasında başlar. `start` ve `close` aynı
synchronized sınırındadır; `started/closed` durumları yinelenen ready olayında
ikinci görev planlanmasını veya kapanmış servisin başlatılmasını engeller.
Normal test profilindeki background-off davranışı, worker/probe sınırları,
cache snapshot sözleşmesi ve retention aralığı korundu.

Yeni `SystemHealthLifecycleTest` gerçek Spring context'inde singleton kurulumu
sırasında probe lookup'ı yapılmadığını, hazır olayından sonra ölçüm oluştuğunu,
duplicate ready/close, test profili ve ready öncesi kapanış sınırlarını doğrular.
Eski kaynakta ilk dört test **3 beklenen FAIL / 1 PASS** verdi. Özellikle gerçek
probe, başka singleton hâlâ initialize edilirken başladı; test bunu yakaladı.
Bu kontrollü test canlı JVM'nin bütün Rabbit kilit döngüsünü taklit etmez.

Son koşu: **10 suite / 37 PASS / 0 FAIL / 0 ERROR / 0 SKIP**, Gradle
BUILD SUCCESSFUL 27 saniye. Yeni lifecycle sınıfı 5/5 PASS; mevcut health ve
collector testleri de çalıştı. İlk GREEN denemesinde iki MVC testindeki
`verifyNoInteractions`, Spring'in yeni ready callback'ini HTTP işlemi sanıyordu.
`@BeforeEach clearInvocations` ile yalnız startup geçmişi ayrıldı; HTTP güvenlik
no-interaction kontrolleri ve üretim kontrolleri korundu.

Kanıtlar `artifacts/ready-for-prod-01/backend/01-a/` altında:

- `health-lifecycle-red/`, `health-lifecycle-red.log`;
- `health-lifecycle-first-green/` (36 test / 34 PASS / 2 fixture FAIL);
- `health-lifecycle-final/` (XML ve `summary.json`), `health-lifecycle-green.log`.

Komut: `gradlew.bat --offline --no-daemon --max-workers=2 test --tests 'com.berkayb.soundconnect.modules.admin.health.*'`.
Gradle slotu yeni bootJar ve tam backend suite için 01-C sahibine devredildi.
01-C ve 01-B sahiplerinin bağımsız dar kaynak incelemelerinde yeni P0/P1/P2
bulgu bildirilmedi. Ürün kaynakları bu sonucu takip eden JAR'da bulunmalıdır;
ilk B3A JAR bu düzeltmeyi içermez.

## Manuel kabul sınırı

Root ilk denemeyi 06:24:08 UTC'de önceki API'ye geri aldı; HTTP UP ve mevcut
36 kullanıcı/540 inbox için hash korunumu, bağımlılık container ID'lerinin
değişmediği bilgisi root'un ayrı rollout kanıtıdır. Bu alt görev runtime'a,
ortak DB'ye veya cihazlara müdahale etmedi. Yeni final JAR ile startup/readiness
ve sağlık cache'inin gerçek ölçüm vermesi ayrıca doğrulanmalıdır. Test sonucu
bu runtime kabulünün veya mobil/görsel kabulün yerine geçmez.
