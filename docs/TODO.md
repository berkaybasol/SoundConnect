## *ASLA UNUTMAMAN GEREKENLER*
- venueapplicationrequestte phone yok onu hallet bir ara
- flutter tarafinda google ile giris yap buton var ama backendi entegre etmedin o da halledilcek
- Pagination'u medya da yapiyorsun suan ama diger modullere buyuk refact gerekiyo olabilir. diger modullerde hangisinde gerekli ogren ve entegre et medya modulunu bitirince.
- PROD oncesi CloudFront Distribution’a custom domain ekle.
- Dockerfile'a ffmpeg ve ffprobe kurulumu yapmayi unutma. 
- ElasticSearch butun moduller bittikten sonra kurulacak.

# SoundConnect SPRINT

---

## *Tamamı Bitmeyenler / Kısmen Bitmişler*

🛠 Veri seed (rol, permission, şehir vs. eksikleri var)

🛠 MVP için belirlenen bütün modüller yazıldıktan sonra endpoint’lere gerekli izinler tanımlanacaktır.

🛠 Forgot Password (useniyorum :D)

🛠 Change Password (useniyorum :D)

🛠 2 yeni profile olusturulacak (Music House Profile, Band Profile(bu registerda degil yalnizca musician profile'a sahip kullanicilarin acabilecegi bir profil olcak.))


---

## *Sprint Planı (Yapılacaklar)*

⏳ 3. İlan Modülü

⏳ 4. Setlist Creator

⏳ 5. Müzik Birleştirir (Masa Aç – Yalnız Değilsin)

⏳ 6. Event Modülü (Konuma göre nerde kim çalıyor?)

⏳ 7. Overthinking Modülü

⏳ 9. Stabilizasyon & MVP Final

⏳ 10. Activity Feed & Admin Monitoring

## *Bitenler*

✅ Notification modülü (RabbitMQ + Redis + WebSocket + MailerSend)

✅ Media modülü (RabbitMQ & AWS S3)

✅ DM modülü

✅ Follow modülü

✅ Profile modülü

✅ Instrument modülü

✅ Mail mimarisi

✅ ArtistVenueConnection modülü

✅ VenueApplication modülü

✅ Google ile OAuth2 register/login

✅ Location modülü (City, District, Neighborhood)

✅ Venue modülü

✅ Auth yapısı (JWT + OTP)

✅ RabbitMQ & MailerSend

✅ CORS & environment config

✅ Logging & SLF4J yapısı

✅ Exception mimarisi

✅ Role & Permission modülü

✅ User modülü