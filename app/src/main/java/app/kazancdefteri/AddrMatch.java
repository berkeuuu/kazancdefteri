package app.kazancdefteri;

import java.util.ArrayList;
import java.util.List;

/**
 * Yerel adres veritabanı satırlarından güvenli bir hedef seçer. Android'e bağımlı değil (test edilebilir).
 * Kural: emin değilse null döner; asla tahmin edilmiş/uydurulmuş koordinat üretmez.
 */
final class AddrMatch {
    private AddrMatch() {
    }

    static final class Row {
        String mahK, mahalle, yol, yolTur, no, noHarf;
        double lat, lon;
    }

    static final class Result {
        double lat, lon;
        int conf;
        String desc;
        /** Güven puanı yeterli ve tek bir yerde toplanıyorsa true: otomatik yönlendirilebilir. */
        boolean auto;
    }

    static final int AUTO_MIN = 80;
    static final double CLUSTER_M = 60.0;

    /** @param rows aynı sokak anahtarı ve aynı bina taban numarasındaki satırlar */
    static Result decide(NavHelper.Query q, List<Row> rows) {
        if (q == null || rows == null || rows.isEmpty()) {
            return null;
        }
        int conf = 92;
        ArrayList<Row> use = new ArrayList<Row>();
        boolean mah = q.mahK.length() > 0;
        if (mah) {
            for (Row r : rows) {
                if (r.mahK.equals(q.mahK)) {
                    use.add(r);
                }
            }
        }
        if (mah && use.isEmpty()) {
            use.addAll(rows);
            conf -= 40; // mahalle uyuşmuyor: asla otomatik
        } else if (!mah) {
            use.addAll(rows);
            conf -= 15;
        }
        String letter = q.no.replaceAll("^\\d+", "").toLowerCase(java.util.Locale.ROOT);
        if (letter.length() > 0) {
            ArrayList<Row> lr = new ArrayList<Row>();
            for (Row r : use) {
                if (r.noHarf.equals(letter) || r.no.toLowerCase(java.util.Locale.ROOT).replace(" ", "").equals(q.no.toLowerCase(java.util.Locale.ROOT))) {
                    lr.add(r);
                }
            }
            if (!lr.isEmpty()) {
                use = lr;
            } else {
                conf -= 10; // "47B" yazılmış ama kayıtta yalnız "47" var
            }
        }
        if (q.strTyp.length() > 0) {
            ArrayList<Row> tr = new ArrayList<Row>();
            for (Row r : use) {
                if (r.yolTur.equals(q.strTyp)) {
                    tr.add(r);
                }
            }
            if (!tr.isEmpty()) {
                use = tr; // aynı isimli cadde ve sokak varsa yazılan türe uyan seçilir
            } else {
                conf -= 5; // "sok." yazılmış, veride cadde (ya da tersi)
            }
        }
        ArrayList<double[]> pts = new ArrayList<double[]>();
        for (Row r : use) {
            pts.add(new double[]{r.lat, r.lon});
        }
        double[] m = NavHelper.medoid(pts);
        double far = 0;
        for (double[] p : pts) {
            far = Math.max(far, NavHelper.dist(m, p));
        }
        Result res = new Result();
        res.lat = m[0];
        res.lon = m[1];
        if (far > CLUSTER_M) {
            conf -= 30; // aynı numara birbirinden uzak yerlerde: belirsiz
        } else if (far > 25) {
            conf -= 5;
        }
        res.conf = Math.max(conf, 0);
        res.auto = res.conf >= AUTO_MIN && far <= CLUSTER_M;
        Row f = use.get(0);
        res.desc = f.mahalle + " " + f.yol + " " + f.no + " (%" + res.conf + ")";
        return res;
    }
}
