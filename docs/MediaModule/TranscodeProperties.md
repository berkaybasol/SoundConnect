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