# RabbitMQ Nedir? 
**RabbitMQ**, mesajlari uygulamalar veya servisler arasinda ileten, acik kaynak kodlu bir message broker (mesaj aracisi) yazilimidir.

Ozetle:
Bir uygulamadan veya servisten cikan verinin, baska bir uygulama veya servise **asenkron**, **guvenilir**, **kuvvetli kuyruklama** sistemiyle aktarilmasini saglar.

--- 

## Neden RabbitMQ Kullanilir?
- **Asenkron Iletisim:**
- Islemleri birbirinden ayirir, "bir islem bitmeden digerine baslanmaz" zorunlulugunu kaldirir.
- **Dagitik sistemler icin gereklilik:**
- Mikroservis mimarilerinde veya birden fazla uygulamanin haberlesmesinde iletisimi standartlastirir.
- **Is yuku dengelemesi ve dayaniklilik:** 
- Yuksek trafikte dahi mesajlari kaybetmeden guvenli sekilde siraya alir.
- **Servis bagimsizligi:**
- Ornegin, bir email gonderimi icin kullanildiginda, kullanici kayit olur, ana sistem hemen cevap doner,
email gonderimi ise arkada islenir. (asenkrona calismaya bir ornek daha..)

--- 

## Temel RabbitMQ Kavramlari
### Islem sirasi:
Producer → Exchange → [Binding] → Queue → Consumer → ACK/NACK

- **Producer (Uretici):**
Mesaji **olusturup kuyruklara gonderen** uygulama/servis.

- **Consumer (Tuketici):**
Kuyrukta bekleyen mesajlari **alip isleyen** uygulama/servis.

- **Queue (Kuyruk):**
Gonderilen mesajlarin siralandigi veri yapisi.
**FIFO (First In, First Out)** prensibiyle calisir.
Bir veya birden fazla consumer tarafindan dinlenebilir.

- **Exchange (Degistirici):**  
  Ureticiden gelen mesaji, **kurallarina gore** ilgili kuyruga yonlendirir.
    - Producer mesaji dogrudan kuyruga gondermez, once Exchange’e yollar.
    - Bir Exhange birden fazla kuyrukla ilgili olabilir.

- **Binding (Baglanti):**
Bir Exchange'in, bir Queue ile **nasil** iliskilendirecegini belirleyen kurallar.

- **Routing Key (Yonlendirme Anahtari):**
Mesajin hangi kuyruklara gidecegini belirlemede kullanilan anahtar. 

- **Messagge (Mesaj):**
Kuyrukta bekleyen veri. (Orn: JSON, string, binary dosya, vb.)

---

## Exchange Turleri

### 1. **Direct Exchange**
- **Aciklama:**
Routing key'e **tam eslesen** kuyruga mesaj yollar
- **Kullanim Senaryosu:**
Belirli bir is tipine ozel kuyruk (orn: sadece "email" mesajlarini alan bir kuyruk).

---

### 2. **Fanout Exchange**
- **Açıklama:**
Routing key'i **dikkate almaz**, bagli **tum kuyruklara** mesaji yollar (broadcast).
- **Kullanim Senaryosu:**
  Bir olayi sistemdeki tum servislere yaymak (orn: bildirim, log, anlik guncelleme).

  ---

### 3. **Topic Exchange**
- **Aciklama:**
Routing Key uzerinde **Pattern eslesmesi** yapilir (`*`, `#` wildcard karakterleriyle).
- **Kullanım Senaryosu:** 
Farkli tip mesajlara filtreli dagitim (orn: user.*`, `payment.#` gibi esnek yonlendirme).

---

### 4. **Headers Exhange**
- **Aciklama:**
Routing Key yerine, **mesaj header'lerine gore** yonlendirme yapilir.
-  **Kullanım Senaryosu:**
Header icerigine bakarak ozellestirilmis mesaj yonlendirmesi yapmak.

---

## Bilmen Gereken Detaylar
- **Mesajlar "acknowledged(consumer basariyla isleyene kadar)" edilene kadar kuyrukta kalir:**
Consumer, "aldim ve isledim" demedikce mesaj tekrar consumer'a gider.

- **Dead Letter Queue (DLQ):**
Islenemeyen, hatali mesajlar icin ozel kuyruk (retry/hata yonetimi icin).

- **Durability ve Persistence:**
Kuyruklar ve mesajlar disk uzerinde tutulacak sekilde yapilandirilirsa, RabbitMQ yeniden baslatilsa bile mesajlar kaybolmaz.

- **Prefetch Count:**
Consumer'in bir seferde alabilecegi mesaj sayisi limiti.

- **High availability (HA) & Cluster Mode:**
Birden fazla cok sunucuya dagitarak sistemin cokmesini engelleyebiliriz.

- **Monitoring & Management:**
Web arayuzu ile canli olarak kuyruk ve mesaj trafigi izlenebilir.