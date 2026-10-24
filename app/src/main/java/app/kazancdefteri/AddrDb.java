package app.kazancdefteri;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Bandırma çevrimdışı adres veritabanı (assets/bandirma_adres.db, kaynak: OpenStreetMap, ODbL).
 * İlk kullanımda uygulama klasörüne kopyalanır; yeni sürümde (meta.surum) otomatik yenilenir.
 */
final class AddrDb {
    private AddrDb() {
    }

    private static final String ASSET = "bandirma_adres.db";
    private static SQLiteDatabase db;
    private static boolean failed;

    private static synchronized SQLiteDatabase open(Context ctx) {
        if (db != null || failed) {
            return db;
        }
        try {
            File f = new File(ctx.getFilesDir(), ASSET);
            // Uygulama her güncellendiğinde (yeni veri gelmiş olabilir) dosya yeniden kopyalanır
            SharedPreferences sp = ctx.getSharedPreferences("nav", Context.MODE_PRIVATE);
            long stamp = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
            if (!f.exists() || sp.getLong("db_ts", -1) != stamp) {
                InputStream in = ctx.getAssets().open(ASSET);
                try {
                    OutputStream out = new FileOutputStream(f);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
                sp.edit().putLong("db_ts", stamp).apply();
            }
            db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {
            failed = true;
            db = null;
        }
        return db;
    }

    /** Veritabanı bilgisi (sürüm, veri tarihi, kayıt sayısı) ya da boş. */
    static String info(Context ctx) {
        SQLiteDatabase d = open(ctx);
        if (d == null) {
            return "";
        }
        String t = "", k = "";
        Cursor c = d.rawQuery("select k,v from meta", null);
        try {
            while (c.moveToNext()) {
                if (c.getString(0).equals("kaynak_tarihi")) {
                    t = c.getString(1);
                } else if (c.getString(0).equals("kayit")) {
                    k = c.getString(1);
                }
            }
        } finally {
            c.close();
        }
        return k + " adres, veri tarihi " + t;
    }

    /** Adres için güvenilir bir hedef; bulunamazsa ya da belirsizse null (eski yöntem devreye girer). */
    static AddrMatch.Result find(Context ctx, NavHelper.Query q) {
        if (q == null || q.strK.length() == 0 || q.no.length() == 0) {
            return null;
        }
        String base = q.no.replaceAll("[^0-9].*$", "");
        if (base.length() == 0) {
            return null;
        }
        SQLiteDatabase d = open(ctx);
        if (d == null) {
            return null;
        }
        ArrayList<AddrMatch.Row> rows = new ArrayList<AddrMatch.Row>();
        Cursor c = d.rawQuery("select mahalle_k,mahalle,yol,yol_tur,no,no_harf,lat,lon from adres "
                + "where yol_k=? and no_taban=?", new String[]{q.strK, base});
        try {
            while (c.moveToNext()) {
                AddrMatch.Row r = new AddrMatch.Row();
                r.mahK = c.getString(0);
                r.mahalle = c.getString(1);
                r.yol = c.getString(2);
                r.yolTur = c.getString(3);
                r.no = c.getString(4);
                r.noHarf = c.getString(5);
                r.lat = c.getDouble(6);
                r.lon = c.getDouble(7);
                rows.add(r);
            }
        } finally {
            c.close();
        }
        return AddrMatch.decide(q, rows);
    }
}
