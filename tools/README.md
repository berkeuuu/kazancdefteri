# Adres veritabanı araçları

`app/src/main/assets/bandirma_adres.db`, OpenStreetMap verisinden üretilir (© OpenStreetMap katkıda bulunanlar, ODbL 1.0 —
https://www.openstreetmap.org/copyright). Uygulama bu dosyayı çevrimdışı okur; Navi düğmesi **önce** kayıtlı teslim konumuna (K),
sonra bu veritabanına (G, sadece güvenli ve tek yerde toplanan eşleşme), olmazsa eski metin yöntemlerine (A–F) başvurur.

## Veriyi yenileme
1. BBBike (https://extract.bbbike.org) ya da Geofabrik'ten Bandırma bölgesini **.osm.pbf** olarak indir (SQLite değil; ona adres etiketleri konmuyor).
2. `python3 -I tools/adres_db_uret.py indirilen.osm.pbf app/src/main/assets/bandirma_adres.db`
3. Commit + push: Actions yeni APK'yı üretir. Uygulama güncellenince veritabanı telefonda kendiliğinden yenilenir.

## Kurallar
- Sadece OSM'de yazılı `addr:*` kayıtları alınır; eksik bina numarası/koordinat uydurulmaz.
- Eski-yeni sokak numarası eşleştirmesi yoktur (ör. "912. Sokak" ↔ "20003 Sokak" doğrulanmadığı için eklenmedi).
- Anahtar üretimi (`fold`/`key`) `NavHelper` ile birebir aynıdır; birini değiştirirsen diğerini de değiştir.
- Eşleşme güveni %80 altındaysa ya da aynı numara birbirinden >60 m uzak kayıtlara aitse otomatik yönlendirme yapılmaz.
