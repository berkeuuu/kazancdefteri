# Kazanç Defteri

PaketTaxi / Migros Hemen 4. Bölge kazanç hesaplayıcı ve Migpack ekran okuyucu (Android).

## APK nasıl çıkar

1. GitHub'da boş bir repo aç (gizli olması iyi olur).
2. Bu klasörün içindekileri repoya yükle (`.github` klasörü dahil).
3. Repoda **Actions** sekmesine gir, **APK derle** akışını çalıştır (Run workflow).
4. Bitince **Releases** bölümünden `KazancDefteri.apk` dosyasını indir ve kur.

Güncelleme için dosyaları değiştirip tekrar çalıştırman yeterli. Aynı imza anahtarı kullanıldığı için yeni sürüm eskisinin üstüne kurulur ve kayıtların silinmez.

## Ekran okuma

Ayarlar > Erişilebilirlik bölümünde "Kazanç Defteri Okuyucu" izni açılır. Okuma yalnızca uygulamadaki "Migpack'ten oku" düğmesine basıldıktan sonraki 5 dakika boyunca ve yalnızca Migpack ekranında çalışır. Uygulamada internet izni yoktur.
