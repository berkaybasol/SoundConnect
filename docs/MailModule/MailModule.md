## 🔗 Sınıfsal Akış Zinciri

| Sıra | Sınıf Adı | Görevi |
|------|-----------|--------|
| 1️⃣ | `MailSendRequest` (DTO) | Mail gönderiminde kullanılacak verileri taşır (`to`, `subject`, `htmlBody`, `textBody`, `kind`, `params`)  
| 2️⃣ | `MailProducerImpl` | Bu DTO’yu RabbitMQ'ya gönderir (`mail.exchange` + `mail.send`)  
| 3️⃣ | `RabbitMQ` | Mesajı `mail.queue` içinde tutar. Gecikme (TTL), DLQ gibi özellikler buradadır.  
| 4️⃣ | `MailJobConsumer` | Kuyruktaki mesajı dinler → Redis lock + idempotency kontrolü yapar → Mail gönderim başlatır  
| 5️⃣ | `MailSenderClientImpl` | MailerSend API’ye WebClient ile çağrı yapar. Başarılıysa süreç tamamlanır  
| 6️⃣ | `MailRetryPublisher` | Eğer gönderim başarısız olursa delay + jitter ile tekrar sıraya yollar  
| 7️⃣ | `DlqMailJobConsumer` | Retry limiti aşılan veya kalıcı hatalarda DLQ’ya düşen mesajları yakalar ve loglar  
| 8️⃣ | `MailJobHelper` | Redis ile çalışan altyapı helper’ıdır: → Idempotency key üretir   → Redis lock/unlock  → Retry delay hesaplar  → Idempotency key üretir  → Rate-limit (429) varsa Retry-After hesabı yapar

 



## 📚 Ek Notlar

- Mesajlar `MailSendRequest` yapısında DTO olarak taşınır.
- Retry mekanizması `mail.retry.delaysMs` ve `useRetryAfter` ayarlarına göre dinamik çalışır.
- Her mail işlemi için Redis ile:
    - Idempotency kontrolü (`aynı mail 2 kere gitmesin`)
    - Lock kontrolü (`aynı anda 2 worker aynı işi yapmasın`)
    - Retry loglaması yapılır.
- DLQ'ya düşen mesajlar `DlqMailJobConsumer` ile detaylı loglanır. (İleride Slack/Sentry entegrasyonu için altyapı hazırdır.)

---

birazdan silcem
1. MailSendRequest
2. MailProducerImpl
3. MailJobConsumer
4. MailSenderClientImpl
5. MailRetryPublisher
6. DlqMailJobConsumer
7. MailJobHelper
8. MailQueueConfig