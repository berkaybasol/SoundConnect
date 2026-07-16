## Media visibility and storage security contract

- Every client upload, including `PUBLIC`, is written first to the distinct, non-CDN `S3_PRIVATE_BUCKET`. Public-intent uploads use `quarantine/`; protected uploads use `protected/`. Neither form receives a stable URL while `UPLOADING`.
- Completion reads the mutable object's authoritative metadata and ETag, then performs an ETag-conditional server-side copy to a client-inaccessible immutable key (`verified/` or `protected/private-verified/`). Size, MIME, and file-signature validation run only against that immutable snapshot. This closes the presigned-PUT overwrite race between validation and publication/transcoding.
- After validation, `PUBLIC` image/audio objects are copied from `verified/` to their deterministic key in `S3_BUCKET`. The database switches to that public key/URL in the same transaction. Transaction rollback removes the new destination; commit removes the mutable upload and intermediate immutable copy.
- A validated `PUBLIC` video source stays under `verified/` in the private bucket for transcoding/recovery. Only server-generated HLS output is written to `S3_BUCKET`; the raw source URL is never exposed. The worker ignores source and destination paths from the Rabbit payload and derives both from the claimed database row and `MediaPolicy`.
- Ready `PRIVATE` and `UNLISTED` progressive objects remain immutable under `protected/private-verified/` in `S3_PRIVATE_BUCKET`. That bucket must not be connected to the public CDN and its objects must not use public-read ACLs.
- Protected object keys are persisted with an internal `protected/` marker. `S3StorageClient` removes that marker when addressing the private bucket; it is never a CDN path.
- A ready protected image/audio object is available only to a principal that can act for its media owner through `GET /api/v1/user/media/{assetId}/access-url`. The response URL is origin-signed, short-lived (`S3_DOWNLOAD_PRESIGN_EXPIRY_SECONDS`, default 300 seconds), and must never be cached or persisted by the backend.
- `UNLISTED` currently follows the same owner-only access policy as `PRIVATE`. Link sharing needs a separate random, revocable share-token contract; object keys are not share tokens.
- Non-public video uploads are rejected. Secure HLS requires authorization for the manifest and every segment (for example signed CDN cookies/URLs), which is not part of the current contract.
- Upload URLs use the independently configurable `S3_UPLOAD_PRESIGN_EXPIRY_SECONDS` (default 900 seconds). Both upload and download expiries must be between 60 and 3600 seconds.
- The private bucket must enforce a lifecycle expiry for `quarantine/` (24 hours is the recommended maximum) and abort incomplete multipart uploads. This is a mandatory crash/IAM-outage backstop, not a replacement for application cleanup retries.
- The application identity needs private-bucket `s3:PutObject/GetObject/DeleteObject`, public-bucket `s3:PutObject/DeleteObject`, and permission on both KMS keys when SSE-KMS is used. S3 copy authorization is the source `GetObject` plus destination `PutObject`; there is no separate IAM `s3:CopyObject` action. Client presigned PUT CORS belongs on the private bucket; clients must not receive direct public-bucket PUT permission.
- Legacy `UPLOADING + PUBLIC` rows whose storage key is not marked `quarantine/` are rejected fail-closed and queued for idempotent cleanup. Roll out the backend before issuing new upload sessions; already `READY` legacy assets need a separate inventory/migration decision.
- Deployment upgrade prerequisite: existing `PRIVATE`/`UNLISTED` objects must be copied out of the public bucket into `S3_PRIVATE_BUCKET`, their database `storageKey` values rewritten with the `protected/` marker, and all stable URL columns cleared. The API intentionally refuses to mint a signed URL for an unmarked legacy key; do not treat the code deployment alone as migration of previously exposed objects.

## Transcode delivery contract

- Database state is the durable dispatch intent: `TRANSCODE_QUEUED` is published with a correlated Rabbit publisher confirm, then becomes `TRANSCODE_SENT`. A bounded recovery scan requeues stale confirmed-but-unclaimed jobs. Duplicate messages are expected and the consumer claims work atomically.
- The consumer accepts only a database-backed `PUBLIC` video in an eligible state with a server-created verified private source. A late success cannot overwrite a terminal `FAILED` row.
- FFmpeg/ffprobe execution has a configurable hard timeout (`FFMPEG_PROCESS_TIMEOUT_SECONDS`, default 3600); timed-out processes are terminated and their reader threads are bounded. A partial HLS upload is compensated by deleting the whole generated prefix before the job fails.
- Stale `PROCESSING` jobs currently become `FAILED` for operator review rather than being retried automatically. A lease/attempt-token model is required before automatic crash retry can safely distinguish an abandoned worker from a slow live worker.

## CDN (Content Delivery Network)
- Dunyanin farkli noktalarindan medya dosyalarini kopyalayan ve kullanicilara en yakin sunucudan hizla yoneten sistem.
- CDN sayesinde kullanicilarin yasadigi yere en yakin noktadan medya (gorsel, muzik, video) cok hizli sekilde ulasir
- hizli yukleme, sunucuya binen yukun azalmasi, DDos korumasi ve olceklenebilirlik saglar.
- Youtube, Spotify, Instagram gibi buyuk firmalar medya icerigini CDN'lerle dagitir. 
- Ornek CDN URL: https://cdn.soundconnect.app/media/abc123.jpg

## Presigned URL
- Belirli bir sureligine (ornegin 1 saat) gecerli olan dosya yukeleme veya indirme yetkisi veren ozel link
- Bu sayede uygulama sunucusu dosya tasimak zorunda kalmaz. kullanici dosyayi dogrudan storage'a (S3/R2) yukler
- **Kullanimi**:
- Backend initUpload gibi bir endpointte bu linki uretir, frontend kullaniciya verir ve dosya dogrudan storage'a yuklenir. yukleme tamamlandiginda backend'e response doneriz.

## HLS (HTTP Live Streaming)
- Video dosyalarinin canli veya kesintisiz izlenebilmesini saglayan bir medya aktarim protokoludur. 
- Videonun tamamini kucuk parcalara boler (Orn 6'sar saniye)
- Kullanici izledikce sirayla internet hizina uygun olarak bu parcalar yuklenir (adaptif bitrate)
- Youtube, Twitch vb. platformlar HLS kullanir. 

## Progressive Streaming (Progressive Download)
- Dosyanin tamami inmeden ilk parcalari oynatmaya/calabilmeye olanak saglayan yontemdir. 
- Profesyonel platformlarda tercih edilmez cunku ileri/geri sarma veya kalite secimi gibi ozellikleri yoktur.
- Gorsel ve Ses dosyalari icin idealdir. 

## Storage Key
- Medya dosyasininin storage'da (S3/R2) bulundugu yol veya anahtar.
- Orn klasor yapisi: media/{assetId}originalname.jpg

## Transcode (Transcoding)
- Bir medya dosyasini farkli formatlara ve kaliteye donusturme islemi
- Orn: yuklenen .mov videoyu .mp4 veya HLS formatina cevirme. 

## MIME (Multipurpose Internet Mail Extensions)
- Bir dosyanin ne turde oldugunu belirten evrensel etikettir.
- Sunucular ve tarayicilar dosyanin nasil acilacagini bu etikete bakarak anlar. 
- orn: image/jpeg, audio/mp3, video/mp4
- MIME dogrulamasi:
 Dosyanin gercekten iddia ettigi turde olup olmadigini anlamak icin yapilan kontroldur. 
Ornegin kotu amacli birisinin resim diye yukledigi sey aslinda kotu amacli bir .exe olabilir mime kontroluyle bunun onune geceriz. 

## FFmpeg
- Acik kaynakli, ucretsiz komut satiri tabanli bir medya isleme yazilimidir
- video ve ses dosyalarini donusturme, kalite ve format degistirme gibi bircok islem icin kullanilir.
- yuklenen bir videoyu farkli kalitelerde ve formatlarda kopyalayabilir. HLS icin segmentlere bolebilir ve sadece ses ve thumbnail cikartabilir.
- medya dosyalarini (mp3,  mp4, wav, mov, flac, m3u8 vs.) otomatik olarak farkli kalite ve formatlara cevirebilir.
- Transcode islemlerinin neredeyse tamami FFmpeg ile yapilir. 
- Youtube, Netflix, Twitch, Spotify, TikTok, Instagram gibi buyuk platformlar da medya altyapilarinda FFmpeg kullanir. 

## Adaptive Bitrate (ABR)
- Farkli kalite ve bitrate'te(hizda) uretilmis video/auidio segmentlerinin kullanicini intenet hizina gore otomatik olarak secilmesi yontemidir.
- ABR icin birden fazla variant/ladder olusturulmasi gereklidir. 
- HLS gibi protokoller ABR'yi destekler.

## master.m3u8
- HLS sisteminde butun kalite seceneklerini tek bir dosyada toplayan ana playlist dosyasidir. 
- Video oynatici once bu dosyayi ister ve kullanicinin internet hizina gore uygun kaliteyi otomatik olarak secer
- bu dosya sayesinde kullanici kaliteyi degistirebilir ve internet hizina gore gecis yapabilir.

## segmentDurationSec
- HLS sisteminde videonun kac saniyelik kucuk parcalara(segmentlere) bolunecegini belirleyen parametredir.
- Tipik deger genelde 2,4 veya 6 saniyedir biz 4 yaptik.
- Neden segmentlere bolme geregi duyuyoruz diye sorarsan MediaModule.md'de HLS'yi okuyabilirsin.

## GOP (Group of Pictures)
- Kisaca kac karede bir ana kare (i-frame) olsun sorusunun cevabidir.
- Ornegin GOP = 48 ise her 48 karede bir tam goruntu yani i-frame bulunur.
- Diger kareler bu ana kareye gore daha az FARK bilgisiyle saklanir. (essek degilsen FARK derken ne kastedildigini anlarsin sevgili berkay basol)
- Video akisi 3 tip kareden olusur:
- * I-frame(Keyframe): Tam goruntu. En yuksek kalite goruntu.
- * P-frame: Sadece onceki kareyle olan farki saklar. daha kucuk boyutlu.
- * B-frame: Hem onceki hem sonraki karelere gore farki saklar. en verimli sikistirmayi saglar.
- GOP bu karelerden kactane olacagini ve I-frame sikligini belirler.

## Preset(x264/x265 Preset)
- Video encoding sirasinda kalite ve hiz arasindaki dengeyi belirleyen parametredir.
- Basitce video ne kadar hizli isleyeyim? ne kadar iyi sikistirayim? sorusunu cevaplar.
- FFmpeg ve benzeri araclarda ozellikle x264 (H.264) ve 265 encoder'larri icin kullanilir.
- ozetlemek gerekirse hizli isletirsen encode suresi kisalir ama kalite duser

## CRF (Constant Rate Factor)
- x264/x265 video encode islemlerinde kaliteyi kontrol eden ana parametre.
- Videoyu sikistirirken hangi kalitede cikis alinacagini belirler.
- daha kucuk deger = daya yuksek kalite.
- CRF degeri 0-51 arasinda olur 0 = lossless, 51 = en dusuk kalite
- En yaygin pratik aralik 18-23
- * 18 - Nerededeys orijinal kalite -> buyuk dosya
- * 21 - Yuksek/standart kalite -> youtube ve streaming'de en cok tercih edilen
- * 23 - iyi kalite, dosya boyutu daha dusuk.
- ozetle CRF kucukse = yuksek kalite, buyuk dosya buyukse = dusuk kalite, kucuk dosya
