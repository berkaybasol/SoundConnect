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
- Mesajlara "adres" (destination) ekler:
    - `/topic/notifications` (tüm kullanıcılara)
    - `/queue/messages` (kişiye özel)
    - `/user/{username}/queue/notifications` (spesifik kullanıcıya)
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

#### Örnek Destinasyonlar:
- `/topic/*` → Broadcast (tüm kullanıcılara)
- `/queue/*` → Noktadan noktaya (kişiye özel)
- `/user/queue/*` → Oturum açmış kullanıcıya özel (Spring ile popüler)

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
   stompClient.subscribe('/topic/notifications', onNotification);
   stompClient.subscribe('/user/queue/notifications', onPrivateNotification);