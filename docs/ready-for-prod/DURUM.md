# Güncel durum — tek durum kaynağı

Son güncelleme: **8 Ekim 2026, 12:28 TRT**.

| Alan | Son durum |
| --- | --- |
| Aşama 01 v1 kapanışı | **KAPANDI — TANIMLI 01 V1 KAPSAMI**; teknik kabul ve kullanıcı görsel onayı tamam |
| Asıl çalışma/kabul | [CALISMA](asamalar/01/CALISMA.md), [MANUEL_KABUL](asamalar/01/MANUEL_KABUL.md), [BAGIMSIZ_INCELEME](asamalar/01/BAGIMSIZ_INCELEME.md) |
| Somut sonraki adım | 02 öncesi tam observability ve PC kapalıyken alarm için bağımsız izleme/barındırma/maliyet görüşmesi |
| 02 durumu | [Görev hazır](asamalar/02/GOREV.md); branch geçişi ve ürün uygulaması görüşmeden önce yapılmayacak |
| Yeni izleme/hizmet | Yeni stack, sürekli dış izleyici veya hizmet satın alma başlatılmadı |
| Çalışan ortamın son gerçek kabulü | Beş servis healthy; asıl Android user0 admin oturumu korundu, geçici user10 kaldırıldı. Bu geçmiş kabul yeni oturumun anlık runtime durumu yerine geçmez |
| Veri korunumu | Özgün 36 kullanıcı/540 inbox ve diğer korunan satırlar ID+hash eş; sahipli bir test hesabı/profil/rol bağı kaldı, toplam 37 kullanıcı |

Bu belgedeki **W/** üst `SoundConnect/` çalışma alanıdır.

## Git yayın ve kapanış kanıtı

| Alan | Frontend | Backend |
| --- | --- | --- |
| Ana dal | `main` | `master` |
| PR | [PR 5](https://github.com/berkaybasol/soundconnect_mobile_231225/pull/5) | [PR 5](https://github.com/berkaybasol/SoundConnect/pull/5) |
| PR kaynak commit'i | `e33ffd17673ffc32e0c8e9a197921278b6f528f5` | `7309c3707b6c51b7b3f0a3ff60c53a49852c6af3` |
| PR CI | SUCCESS: 7126 Flutter PASS / 2 mevcut SKIP; analyze temiz; coverage %78,50; 457 JVM / 138 instrumentation PASS. Koşu bağlantısı: [37745964735](https://github.com/berkaybasol/soundconnect_mobile_231225/actions/runs/37745964735) | [37747634929](https://github.com/berkaybasol/SoundConnect/actions/runs/37747634929); sonuç ve kapsam: SUCCESS; 753 suite / 6056 PASS / 0 hata; 101 normal skip + 18 parametre şablonu, aşama 454 PASS / 0 skip; monitor 15, migration 19 senaryo / 307 kontrol PASS |
| Normal merge sonucu / commit | MERGED; `d94487ea47a16e5f2dd3882181182010bc7a48cf` | MERGED; `05d0e11e15d6d7523d4274e4c9caca4e4b0102f9` |
| Merge sonrası ana dal CI | [37748383148](https://github.com/berkaybasol/soundconnect_mobile_231225/actions/runs/37748383148): SUCCESS; tüm üç kapı, 7126 Flutter PASS / 2 mevcut SKIP, analyze temiz, 457 JVM ve emülatör kapısı başarılı | [37751998581](https://github.com/berkaybasol/SoundConnect/actions/runs/37751998581): SUCCESS; 6056 PASS / 0 hata, aynı mevcut skip kümesi ve zorunlu rapor kapıları doğrulandı |
| Ürün merge sonrası kontrol kesimindeki checkout / HEAD | `main / d94487ea47a16e5f2dd3882181182010bc7a48cf` | `master / 05d0e11e15d6d7523d4274e4c9caca4e4b0102f9` |
| Canlı uzak ref / kaynak eşliği | Canlı `origin/main` ürün merge SHA'sıyla eş; Git tree `b64e4340a920e459c17462cc4e13427e209abf64` PR ile aynı | Canlı `origin/master` ürün merge SHA'sıyla eş; Git tree `b0612fed1ac28ab269dccb8d38ebfb9a81b4e040` PR ile aynı |
| Ürün merge sonrası kontrol kesimindeki ağaç | Temiz; 02 açılmadı | Temiz; 02 açılmadı |

Tablo ürün yayınının doğrulanmış kesimini kaydeder. Bunun ardından yalnız bu
kapanış belgeleri için commit/PR yapılabilir; kendi son belge commit'ine
öz-referans içermez. Fiilî son HEAD/branch/diff ve uzak eşlik yeni oturumda
Git'ten taze okunur; eski kesim bugünün checkout'u diye varsayılmaz.

Yayın kaynak doğrulaması, kabul manifestindeki **2234 backend / 1623 frontend**
kaynak/test/ilgili yapılandırma girdisi üzerinden kaydedilir; tüm Git tree'sinin
veya bütün büyük artefaktların aynı olduğu anlamına gelmez. Son karşılaştırma
sonucu/kanıt: Kanonik Git kaynak sürekliliği PASS; PR ile ürün merge tree'leri eş, ürün diff'i yok. Checkout öncesi 2234 BE / 1623 FE ham hash eşliği kaydedildi. Checkout sonrası BE'de 2187 ham hash aynı, 37 dosyada yalnız LF/CRLF farkı kabul SHA'sı yeniden kurularak doğrulandı. Diğer 10 dosyanın eski ham satır sonu düzeni yeniden oluşturulamadı; bu 10 dosyanın kaynak bağı checkout öncesi temiz 7309c370/hash kaydı ve değişmeyen Git blob/tree zinciriyle doğrulandı. FE'de 1622 ham hash aynı, yalnız app_route_guard.dart CRLF→LF farkı kabul SHA'sıyla doğrulandı. Bütün ham baytlar eş veya 10 eski düzen yeniden kuruldu denmez. Kanıt: W/artifacts/ready-for-prod-01-20261008/publication/source-verification.json ve backend-source-provenance-proof.json. Belge/CI değişiklikleri ve test edilmiş ürün
kaynağı kapsamı açıkça ayrılır. Git dışı özel veri/dump/token/anahtar ve büyük
APK/JAR dosyaları yayın kapsamı değildir. Ana dal CI/uzak eşlik sonuçları
doğrulanmadan aşama kapanışı veya 02 branch hazırlığı sonucu çıkarılmaz.

## Doğrulanmış paket ve kabul

| Kimlik | Değer |
| --- | --- |
| Son JAR SHA-256 | `ed1ba65697aa9fceb1c23b0620d0087aff8cc20893ce7a4322ff8b80459b34a1` |
| Son API image | `sha256:249a4ddc6d7734fa7b84318bc78aaa18ca77e73337e10a2ad73408a97ebcf7b7` |
| Son normal debug APK | `ready01-final03-debug.apk`, 225026836 bayt |
| APK SHA-256 | `8122a81c10d8fc272a342faf1a61111eb806a9101dc8888abcbeef2bf0e67b34` |
| Mobil kabul ortamı | `emulator-5554`, `tr.com.soundconnect.app`, `local`; kurulu paket SHA eşliği doğrulandı |

Paketler kaynak başlangıç HEAD'lerine değil, çalışma sırasında alınan manifest
ve paket hashlerine bağlıdır. Güncel yayın commit/merge kimlikleri yukarıdadır;
CI APK'sı ile yerel kabul APK'sı ayrıca eşliği doğrulanmadan aynı paket sayılmaz.

| Kabul katmanı | Sonuç ve sınırı |
| --- | --- |
| Auth/session | Public reset ve yönetici parola değişimi atomik session_version ile eski HTTP/WS etkisini kesiyor; yeni giriş çalışıyor. Hedefli test ve bağımsız kaynak incelemesi geçti |
| Limiter | İzole gerçek Redis kesintisinde login/forgot/reset 503 + Retry-After 5; recovery 200. Shared Redis arıza için durdurulmadı |
| Gerçek API/DB/realtime | Sahipli PG/Redis/Rabbit ile HTTP/STOMP 2/2 PASS; açık eski subscriber outbound teslimi reddedildi, yeni subscriber aldı. Normal son API rollout/readiness ve admin 200/401/403 ayrı doğrulandı |
| Sağlık ve mobil tanılama | Pasif sağlık/birikme/eskime görünümü, güvenli mobil raporun DB/UI kaydı ve sınırlı FRAME_TIMING iletimi doğrulandı; native crash/ANR kabulü değildir |
| Son normal APK | Sağlık/rapor/eski ölçüm/toparlanma; reset sonrası eski mobil oturumdan otomatik login ekranına dönüş ve yeni parola ile korumalı Mesajlar ekranı PASS |
| İnsan görsel onayı | 8 Ekim doğrudan “okey gerekli kontrolleri yaptıysan onaylıyorum o zaman” cevabı; final03 ve gösterilen sağlık/reset-yeniden giriş yolları içindir |
| Harici alarm | Gerçek izole connector kesintisi/toparlanması → iki TEST; iki alıcıya toplam dört provider delivered ve bağlı Gmail'de iki INBOX. Kullanıcının okuduğu veya tüm PC kesintisi iddia edilmez |
| Veri/servis koruma | Özgün 36 hesap, 9 rol, 35 bağ, 16 müzisyen profili, 540 inbox, 105 kampanya, 99 occurrence, 190 recipient ID+hash eş. DB/Redis/Rabbit restart edilmedi; yetkili API/worker rollout ayrı kayıtlı |
| Bağımsız inceleme | Raporlanan dar alanlarda açık doğrulanmış geçiş engelleyici teknik bulgu 0; yazarlık/inceleme ve gerçek kabul sahipliği raporda ayrıdır |

## Yerel test ile hosted CI ayrımı

- Son standart yerel backend: **753 suite / 6056 PASS / 0 hata**. Ham 119 XML
  skip = 101 mevcut parametresiz kayıt + 18 açılmamış parametre şablonu; aşama
  ile ilgili 56 suite / 454 testte skip 0. Üretim yük/SLA testi değildir.
- Yerel tam Flutter: **7123 PASS / 2 mevcut görsel SKIP**, tam analyze temiz;
  bu koşu iki son sağlık P3 düzeltmesinden öncedir. Son değişikliklerde ayrı
  **13 sağlık testi PASS** ve 3 dosya analyze temiz. Bu sayılar toplanmaz.
- Son kaynak için frontend PR CI **7126 PASS / 2 mevcut SKIP**, tam analyze,
  coverage %78,50, 457 JVM ve 138 instrumentation PASS verdi. Yerel normal APK
  kabulü yerine hosted instrumentation sonucu kullanılmaz.
- Yerel Python monitor **15 PASS**; migration launcher **19 senaryo / 307
  kontrol PASS**. Launcher eski 15 SQL'yi sınar; yeni iki 8 Ekim SQL'nin gerçek
  uygulanması runtime/PG kabulünde ayrıca kayıtlıdır.
- Backend PR ve her iki ana dal CI kapsamı/sonuçları yukarıdaki yayın tablosuna
  gerçek kanıt geldikçe yazılır; yerel test başarısından çıkarılmaz.

## Sıradaki görüşme ve açık sınırlar

Kullanıcının son talimatı: “PC kapalıyken de gelsin de sen mevcut işini bitir de
bu konuyu detaylı bir konuşalım 02den önce”. Önce 01 v1'in onaylı kapanışı;
sonra kapsamlı izleme ve bağımsız dış alarmın kapsamı, barındırması, maliyeti ve
kabulü konuşulur. 02 branch/ürün işi ve yeni izleme kurulumu kendiliğinden açılmaz.

V1; son pencere istek/5xx/**ortalama** gecikme ve sağlık/birikme ölçümleridir.
CPU/RAM/disk/JVM paneli, kalıcı zaman serileri/grafikler, p95/p99, merkezi
exception araması/dosya-satır teşhisi ve dağıtık trace uygulanmadı. Sürekli dış
host izleyicisi ve tüm bilgisayar/elektrik/ağ kesintisi kabulü yoktur. Yeni
kurulum veya harici mesaj yetkisi önceki iki TEST mailinden türetilmez.

8 Ekim mobil kabulünde kullanıcı emülatörü seçti; fiziksel Vivo kullanılmadı.
Bu oturum istisnası sonraki oturumlara kalıcı muafiyet değildir. iOS, üretim
fleet/ingress/yük/SLA, canlı dağıtım ve Analytics kabulü yapılmadı. 06 hedef
repo/hesapları ve 07 Apple/imza/cihaz erişimi ayrıca belirlenir.

## Korunan önceki işler ve kanıt erişimi

Bildirim geliştirme/özel bildirim kabulü önceki kapsamıyla kapalıdır:
[önceki yayın](../../../artifacts/notification-publication-20261008/DELIVERY.md),
[özel bildirim kabulü](../../../artifacts/custom-notifications-final-acceptance-20261008/DELIVERY.md).
Bu dosyalar aynı üst çalışma alanını gerektirir; GitHub'da veya tek repo
kopyasında bulunmayabilir. Önceki PASS'ler yeni aşamanın sonuçlarına eklenmez.
Dar P3 ileri cihaz saati/Dismissible silme-rollback kaydı önceki kapanışı
engellemeyen kapsam dışı iştir; yeni uygulama kendiliğinden başlatılmaz.
Eski `.bil007-ci` worktree'leri tarihsel kanıttır, aktif checkout sayılmaz.
