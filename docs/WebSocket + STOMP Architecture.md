# WebSocket + STOMP Mimarisi (Future-Proof, Açıklamalı Rehber)

## WebSocket Nedir?

- WebSocket, **client (istemci)** ve **server (sunucu)** arasında **çift yönlü (duplex)** ve sürekli açık bir bağlantı sağlayan bir iletişim protokolüdür.
- Klasik HTTP'de olduğu gibi sürekli "istek-yanıt" yoktur.  
  Bağlantı kurulduktan sonra hem istemci hem de sunucu **anında veri gönderebilir**.
- **Anlık veri akışı** gerektiren uygulamalar (chat, bildirim, canlı skor, müzik yayını, finansal veri, metaverse) için idealdir.
- **Bağlantı Akışı:**
    1. Client, HTTP üzerinden WebSocket bağlantısı başlatır (Handshake).
    2. Bağlantı kurulduktan sonra iletişim TCP üzerinden devam eder ve bağlantı sürekli açık kalır.
    3. İki taraf da istediği zaman mesaj gönderebilir.

---

## STOMP Nedir?

- **STOMP (Simple Text Oriented Messaging Protocol):**  
  WebSocket üzerinde çalışan, mesajların nasıl yönlendirileceğini ve yönetileceğini belirleyen, **açık ve basit** bir mesajlaşma protokolüdür.
- WebSocket **ham veri** taşır, STOMP ise bu veriyi **adresler, yönlendirir ve yönetir**.
- Java (Spring), Python, Node.js gibi birçok teknolojide kolayca entegre edilebilir.
- Mesajlara "adres" (destination) ekler. SoundConnect broker topic'leri
  `/topic/<routing-key>` biçimindedir; routing-key alt parçaları RabbitMQ ile
  uyumlu olacak şekilde noktayla ayrılır.
- STOMP protokolünde mesajlar **komutlar** ile yönetilir.

---

### STOMP Komutları ve Açıklamaları

| Komut        | Açıklama                                                        |
|--------------|-----------------------------------------------------------------|
| CONNECT      | Client, sunucuya bağlanmak istediğinde ilk gönderdiği komut.    |
| SUBSCRIBE    | Belirli bir kanala (destination) abone olma komutu.             |
| UNSUBSCRIBE  | Abone olunan kanaldan ayrılma komutu.                           |
| SEND         | Belirli bir destination'a (kanala) mesaj gönderme komutu.       |
| MESSAGE      | Sunucudan gelen mesaj (SUBSCRIBE olunan kanaldan alınır).       |
| DISCONNECT   | Bağlantıyı sonlandırma komutu.                                  |

#### Canonical broker destination sözleşmesi

| Akış | SUBSCRIBE destination |
|---|---|
| Bildirim | `/topic/notifications.<userId>` |
| Bildirim badge | `/topic/notifications.<userId>.badge` |
| DM | `/topic/dm.<userId>` |
| DM badge | `/topic/dm.<userId>.badge` |
| TableGroup | `/topic/table-group.<tableGroupId>` |
| Pulse oda | `/topic/pulse.<roomId>` |
| Pulse alt akışları | `/topic/pulse.<roomId>.(vote|state|presence)` |

`/topic/` sonrasında ham `/` kullanılmaz. RabbitMQ STOMP bu bölümü AMQP topic
routing key olarak yorumlar ve slash-separated eski biçimleri reddeder.

İlk sürümde TableGroup sohbet yazımı application-level cevap ve kararlı hata
kodu sağlayan authenticated REST `POST` üzerinden yapılır. TableGroup STOMP
kanalı yalnız sunucudan istemciye canlı teslimat içindir; `/app/table-group/...`
gönderim destination'ı yayınlanmaz. Pulse'ın incelenmiş `/app/...` komutları
broker routing key'i değildir ve ayrı uygulama mesajları olarak kalır.

---

## WebSocket + STOMP Mimarisi: Akış ve Senaryolar

### Temel Akış

1. **Bağlantı:**  
   Client, WebSocket endpointine (`ws://host/ws`) bağlanır.  
   JWT veya session ile kimlik doğrulama yapılabilir.

2. **Abone Olma (SUBSCRIBE):**  
   Client, ilgi duyduğu bir topic veya queue'ya abone olur.  
   Örnek:
   ```js
   stompClient.subscribe('/topic/notifications.' + userId, onNotification);
   stompClient.subscribe('/topic/notifications.' + userId + '.badge', onBadge);
   ```
