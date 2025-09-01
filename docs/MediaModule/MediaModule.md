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