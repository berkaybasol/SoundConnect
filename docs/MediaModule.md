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