# Redis ile ilgili kisisel notlarim

## StringRedisTemplate
- Bu sinif Spring Data Redis tarafindan sunulan bir template'dir.
- Baglandi yonetimini otomatik yapar
- Serilestirme islemlerini halleder
- Redis komutlarini Java methodlariyla kullanmamizi saglar.
##### En cok kullanilan StringRedisTemplate methodlari:
- opsForValue(): Basit key-value islemleri icin kullanilir (String -> String)