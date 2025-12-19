## *ASLA UNUTMAMAN GEREKENLER*
- venueapplicationrequestte phone yok onu hallet bir ara
- flutter tarafinda google ile giris yap buton var ama backendi entegre etmedin o da halledilcek
- Pagination'u medya da yapiyorsun suan ama diger modullere buyuk refact gerekiyo olabilir. diger modullerde hangisinde gerekli ogren ve entegre et
- PROD oncesi CloudFront Distribution’a custom domain ekle.
- Dockerfile'a ffmpeg ve ffprobe kurulumu yapmayi unutma. 
- ElasticSearch butun moduller bittikten sonra gereklki yerlere kurulacak.
- Pulse modulundekı default konular yml'den degıscek.

# SoundConnect SPRINT

---

## *Tamamı Bitmeyenler / Kısmen Bitmişler*

🛠 Veri seed (rol, permission, şehir vs. eksikleri var)

🛠 MVP için belirlenen bütün modüller yazıldıktan sonra endpoint’lere gerekli izinler tanımlanacaktır.

🛠 Forgot Password (useniyorum :D)

🛠 Change Password (useniyorum :D)

🛠 2 yeni profile olusturulacak (Music House Profile, Manager Profile

🛠 Profillerde videolar / sesler alani olcak dedik sesleri hallettik ama videolar icin de bir sey gerekiyor mu? onu gpt askimla arastir profilleri komple bitir

🛠 Comment, Like, Media modullerinin gerekli modullere entegresi (core)

---

## *Sprint Planı (Yapılacaklar)*

⏳ 10. Activity Feed & Admin Monitoring (core)


## *Bitenler*

✅ Konu bazli allchat Modülü(Pulse) (mainstage)

✅ Setlist Creator (musician profile'larda ve band olustugunda band icinde gozukcek) (backstage)

✅ Track Modülü (core)

✅ Overthinking Modülü (mainstage)

✅ Like Modülü (core)

✅ Comment Modülü (core)

✅ Collab Modülü (backstage)

✅ Event Modülü (Konuma göre nerde kim çalıyor?) (mainstage)

✅ MusicianProfile'a sahip kullanicilar icin Band sistemi (backstage)

✅ Müzik Birleştirir (Table Group) (mainstage)

✅ Notification modülü (RabbitMQ + Redis + WebSocket + MailerSend) (core)

✅ Media modülü (RabbitMQ & AWS S3) (core)

✅ DM modülü (core)

✅ Follow modülü (core)

✅ Profile modülü (core)

✅ Instrument modülü (core)

✅ Mail mimarisi (RabbitMQ, Redis) (core)

✅ ArtistVenueConnection modülü (backstage)

✅ VenueApplication modülü (backstage)

✅ Google ile OAuth2 register/login (core)

✅ Location modülü (City, District, Neighborhood) (core)

✅ Venue modülü (core)

✅ Auth yapısı (JWT + OTP) (core)

✅ RabbitMQ & MailerSend (core)

✅ CORS & environment config (core)

✅ Logging & SLF4J yapısı (core)

✅ Exception mimarisi (core)

✅ Role & Permission modülü (core)

✅ User modülü (core)