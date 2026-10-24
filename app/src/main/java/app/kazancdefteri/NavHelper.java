package app.kazancdefteri;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migpack "Görev Detayı" ekranındaki Teslimat Adresi yazısını Yandex Navigasyon'un
 * anlayacağı arama yazılarına çevirir (birden fazla yol, sırayla denenir).
 *
 * Yollar:
 *   A  sadece sokak/cadde + bina no   "Hamit Kaplan Caddesi 38 Bandırma Balıkesir"
 *   B  mahalle + sokak + no           "Paşakonak Mahallesi Hamit Kaplan Caddesi No 38 Bandırma Balıkesir"
 *   C  müşterinin "|" sonrası tarifi  "... eski/yeni adı, müşterinin yazdığı gibi (temizlenmiş)"
 * Sadece numarayla anılan sokaklarda ("46. Sokak") A yoktur, çünkü numaralı sokak birçok mahallede var.
 *
 * Tanınan kısaltmalar: Mh. Mah. m. (mahalle), C. Cd. Cad. (cadde), S. Sk. Sok. (sokak), Blv. Bulv. (bulvar).
 * "HamitKaplan" gibi bitişik yazılan adlar ayrılır. Eksik "Cadde/Sokak" kelimesi tariften tamamlanır.
 */
public final class NavHelper {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Locale TR = new Locale("tr", "TR");

    private static final Pattern BINA = Pattern.compile("\\bBina\\s*No\\s*:?\\s*([\\p{L}\\p{N}/\\-]+)", FLAGS);
    private static final Pattern NO_ONLY = Pattern.compile("\\bNo\\s*:?\\s*(\\d+[\\p{L}]?)", FLAGS);
    private static final Pattern KAT_DAIRE = Pattern.compile("\\b(Kat|Daire)\\s*(No)?\\s*:?\\s*[\\p{L}\\p{N}/\\-]*", FLAGS);
    private static final Pattern ILCE = Pattern.compile("([\\p{L}]+)\\s*/\\s*([\\p{L}]+)\\s*$");
    private static final Pattern CAMEL = Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})");
    private static final Pattern ESKI_ADRES = Pattern.compile("^\\s*(eski\\s+adres|adres(\\s+tarifi)?)\\s*[:\\-]?\\s*", FLAGS);
    private static final Pattern SLASH_NUM = Pattern.compile("^(\\d+)/\\d+$");
    private static final Pattern NO_COLON = Pattern.compile("^no:(\\d+)(/\\d+)?$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");
    private static final Pattern BLOK_NUM = Pattern.compile("\\bblok\\s*/?\\s*(\\d+[A-Za-z]?)", FLAGS);
    private static final Pattern NUM_LETTER = Pattern.compile("^(\\d+)[A-Za-z]$");

    private static final HashSet<String> TYPES = new HashSet<String>(Arrays.asList(
            "caddesi", "cadde", "sokak", "sokagi", "bulvari", "bulvar", "yolu", "cikmazi", "cikmaz", "meydani"));
    private static final HashSet<String> CUT = new HashSet<String>(Arrays.asList(
            "daire", "kat", "apt", "apartman", "apartmani", "site", "sitesi", "blok", "blogu", "d:", "ap."));

    private NavHelper() {
    }

    /** Denenecek arama yazıları (sıralı), her birinin türü (A/B/C) ve öğrenme anahtarı. */
    public static final class Query {
        public final ArrayList<String> texts = new ArrayList<String>();
        public final ArrayList<Character> kinds = new ArrayList<Character>();
        public String key = "";
        /** Bina kimliği: mahalle + sokak + bina no (daire hariç). Bina no yoksa boş. */
        public String bkey = "";
        /** Veritabanı eşleştirmesi için: mahalle (sonekli "Mahallesi" olmadan), sokak adı anahtarı, sokak türü, bina no (harfli, "/" öncesi). */
        public String mahK = "";
        public String strK = "";
        public String strTyp = "";
        public String no = "";

        void add(String text, char kind) {
            String k = key(text.replaceAll("(?i)\\bno\\b", ""));
            for (String t : texts) {
                if (key(t.replaceAll("(?i)\\bno\\b", "")).equals(k)) {
                    return;
                }
            }
            texts.add(text);
            kinds.add(kind);
        }
    }

    // ------------------------------------------------------------------ yardımcılar

    /** Küçük harf, Türkçe karakterler sade harfe çevrilmiş hali (karşılaştırma için). */
    static String fold(String s) {
        String t = s.toLowerCase(TR);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            switch (c) {
                case 'ç': sb.append('c'); break;
                case 'ğ': sb.append('g'); break;
                case 'ı': sb.append('i'); break;
                case 'ö': sb.append('o'); break;
                case 'ş': sb.append('s'); break;
                case 'ü': sb.append('u'); break;
                case 'â': sb.append('a'); break;
                case 'î': sb.append('i'); break;
                case 'û': sb.append('u'); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Sadece harf ve rakamlar, sade: boşluk/nokta/büyük-küçük harf farkı olmadan karşılaştırma. */
    static String key(String s) {
        return fold(s).replaceAll("[^a-z0-9]", "");
    }

    private static boolean isType(String tok) {
        return TYPES.contains(fold(tok));
    }

    private static String join(java.util.List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String expandToken(String raw) {
        String tok = raw.replaceAll("[,;]+$", "");
        String f = fold(tok);
        if (f.equals("mh.") || f.equals("mh") || f.equals("mah.") || f.equals("mah") || f.equals("m.")
                || f.equals("mahalle") || f.equals("mahallesi")) {
            return "Mahallesi";
        }
        if (f.equals("c.") || f.equals("cd.") || f.equals("cad.") || f.equals("cd") || f.equals("cad")
                || f.equals("cadde") || f.equals("caddesi")) {
            return "Caddesi";
        }
        if (f.equals("s.") || f.equals("sk.") || f.equals("sok.") || f.equals("sk") || f.equals("sok")) {
            return "Sokak";
        }
        if (f.equals("blv.") || f.equals("bulv.") || f.equals("blv") || f.equals("bulv") || f.equals("bulvar")) {
            return "Bulvarı";
        }
        return tok;
    }

    /** "HamitKaplan Mh." -> ["Hamit", "Kaplan", "Mahallesi"] */
    private static ArrayList<String> tokenize(String s) {
        String t = CAMEL.matcher(s).replaceAll(" ").trim();
        ArrayList<String> out = new ArrayList<String>();
        if (t.length() == 0) {
            return out;
        }
        for (String p : t.split("\\s+")) {
            String e = expandToken(p);
            if (e.matches("^\\d+\\.$")) {
                e = e.substring(0, e.length() - 1);
            }
            if (e.length() > 0) {
                out.add(e);
            }
        }
        return out;
    }

    /** Gerçek adres satırı mı? Düğme etiketleri, başlıklar gibi yazıları eler. */
    public static boolean looksLikeAddress(String t) {
        if (t == null || t.length() < 12 || t.indexOf(' ') < 0) {
            return false;
        }
        String first = t.split("\\|")[0].trim();
        String low = fold(t);
        return BINA.matcher(t).find()
                || ILCE.matcher(first).find()
                || low.contains("mahalle")
                || Pattern.compile("\\b(mh|mah)\\.?(\\s|$)", FLAGS).matcher(t).find();
    }

    // ------------------------------------------------------------------ ayrıştırma

    public static Query parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replace('\n', ' ').trim();
        if (text.length() < 6) {
            return null;
        }
        String left = text;
        String right = "";
        int bar = text.indexOf('|');
        if (bar >= 0) {
            left = text.substring(0, bar).trim();
            right = text.substring(bar + 1).trim();
        }

        String district = "Bandırma Balıkesir";
        Matcher mi = ILCE.matcher(left);
        if (mi.find()) {
            district = mi.group(1) + " " + mi.group(2);
            left = left.substring(0, mi.start()).trim();
        }

        String number = "";
        String leftFull = left;
        Matcher mb = BINA.matcher(left);
        if (mb.find()) {
            number = mb.group(1);
            left = left.substring(0, mb.start()).trim();
            if (!number.matches(".*\\d.*")) {
                // "Bina No:A Blok /45" -> bina no 45
                Matcher bn = BLOK_NUM.matcher(leftFull);
                if (!bn.find()) {
                    bn = BLOK_NUM.matcher(right);
                    if (!bn.find()) {
                        bn = null;
                    }
                }
                number = bn != null ? bn.group(1) : "";
            }
        } else {
            Matcher mn = NO_ONLY.matcher(left);
            if (mn.find()) {
                number = mn.group(1);
                left = left.substring(0, mn.start()).trim();
            }
        }
        int sl = number.indexOf('/');
        if (sl > 0) {
            number = number.substring(0, sl);
        }
        left = KAT_DAIRE.matcher(left).replaceAll("").trim();

        ArrayList<String> lt = tokenize(left);
        if (lt.isEmpty()) {
            return null;
        }
        int mIdx = -1;
        for (int i = 0; i < lt.size(); i++) {
            if (lt.get(i).equals("Mahallesi")) {
                mIdx = i;
                break;
            }
        }
        ArrayList<String> mahalle = new ArrayList<String>(lt.subList(0, mIdx + 1));
        ArrayList<String> street = new ArrayList<String>(lt.subList(mIdx + 1, lt.size()));
        // Haritadan kopyalanmış adreslerde sokaktan sonra ilçe, il kodu, posta kodu, "Türkiye" gelir: kes
        for (int i = 1; i < street.size(); i++) {
            String f = fold(street.get(i));
            if (f.startsWith("bandirma") || f.startsWith("balikesir") || f.startsWith("turkiye")
                    || street.get(i).matches("^\\d{5}$")) {
                street = new ArrayList<String>(street.subList(0, i));
                break;
            }
        }
        String mahalleStr = join(mahalle);

        Query q = new Query();
        if (street.isEmpty()) {
            q.add(mahalleStr + (number.length() > 0 ? " No " + number : "") + " " + district, 'B');
            q.key = key(mahalleStr);
            return q;
        }

        String type = "";
        ArrayList<String> name = new ArrayList<String>(street);
        if (isType(name.get(name.size() - 1)) && name.size() > 1) {
            type = name.remove(name.size() - 1);
        } else if (isType(name.get(name.size() - 1))) {
            // sadece "Sokak" yazıyorsa ad yok
            type = name.remove(name.size() - 1);
        }
        boolean numbered = name.size() == 1 && DIGITS.matcher(name.get(0)).matches();
        for (int i = 0; i < name.size(); i++) {
            String w = name.get(i);
            if (w.length() > 1 && w.equals(w.toLowerCase(TR)) && Character.isLetter(w.charAt(0))) {
                name.set(i, w.substring(0, 1).toUpperCase(TR) + w.substring(1));
            }
        }

        ArrayList<String> rt = new ArrayList<String>();
        if (right.length() > 0) {
            rt = tokenize(ESKI_ADRES.matcher(right).replaceFirst(""));
        }

        // Tür yoksa tariften bul ve bitişik yazılan adı düzelt
        if (type.length() == 0 && !name.isEmpty() && !numbered) {
            String nk = key(join(name));
            for (int w = 1; w <= 4 && type.length() == 0; w++) {
                for (int j = 0; j + w <= rt.size(); j++) {
                    if (key(join(rt.subList(j, j + w))).equals(nk)
                            && j + w < rt.size() && isType(rt.get(j + w))) {
                        type = rt.get(j + w);
                        break;
                    }
                }
            }
        }

        String streetStr;
        if (numbered) {
            // numaralı sokak resmî yazımı: "912. Sokak"
            streetStr = join(name) + ". " + (type.length() > 0 ? type : "Sokak");
        } else {
            streetStr = join(name) + (type.length() > 0 ? " " + type : "");
        }
        if (name.isEmpty() && type.length() > 0) {
            streetStr = type;
        }

        String numPart = number.length() > 0 ? number : "";

        // D hazırlığı: "sok." yazılmış ama gerçekte cadde olabilir (ya da tersi)
        String dText = null;
        boolean dFirst = false;
        if (!numbered && !name.isEmpty() && type.length() > 0) {
            String ft = fold(type);
            String swapped = (ft.startsWith("sokak") || ft.startsWith("sokag")) ? "Caddesi"
                    : (ft.startsWith("cadde") ? "Sokak" : "");
            if (swapped.length() > 0) {
                dText = join(name) + " " + swapped + (numPart.length() > 0 ? " " + numPart : "") + " " + district;
                // Müşteri tarifinde diğer türü anıyorsa ("caddede...") bu yol ikinci sırada denensin
                String hint = fold(right) + " " + fold(join(rt));
                boolean cadHint = hint.contains("cadde");
                boolean sokHint = hint.contains("sokak");
                dFirst = (swapped.equals("Caddesi") && cadHint && !sokHint)
                        || (swapped.equals("Sokak") && sokHint && !cadHint);
            }
        }

        // A: sokak + no (numaralı sokakta yok)
        if (!numbered && !name.isEmpty()) {
            q.add(streetStr + (numPart.length() > 0 ? " " + numPart : "") + " " + district, 'A');
            if (dFirst && dText != null) {
                q.add(dText, 'D');
            }
        }
        // B: mahalle + sokak + no
        q.add((mahalleStr.length() > 0 ? mahalleStr + " " : "") + streetStr
                + (numPart.length() > 0 ? " No " + numPart : "") + " " + district, 'B');

        // C: müşterinin tarifi
        String c = tarifText(rt, name, mahalle, district);
        if (c != null) {
            q.add(c, 'C');
        }

        // D: "sok." yazılmış ama gerçekte cadde olabilir (ya da tersi): türü değiştir, mahallesiz
        if (!numbered && !name.isEmpty() && type.length() > 0) {
            if (dText != null && !dFirst) {
                q.add(dText, 'D');
            }
            // E: türsüz, mahalleli ("Mareşal Fevzi Çakmak 30")
            q.add((mahalleStr.length() > 0 ? mahalleStr + " " : "") + join(name)
                    + (numPart.length() > 0 ? " " + numPart : "") + " " + district, 'E');
        }
        // F: "47B" gibi harfli bina no, harfsiz dene
        Matcher nl = NUM_LETTER.matcher(numPart);
        if (nl.matches()) {
            String dn = nl.group(1);
            if (numbered) {
                q.add((mahalleStr.length() > 0 ? mahalleStr + " " : "") + streetStr + " No " + dn + " " + district, 'F');
            } else {
                q.add(streetStr + " " + dn + " " + district, 'F');
            }
        }

        q.key = key(mahalleStr + " " + join(name));
        q.bkey = number.length() > 0 ? q.key + "_" + key(number) : "";
        q.mahK = key(mahalleStr.replaceAll("(?i)\\s*mahallesi\\s*$", ""));
        q.strK = key(join(name));
        String ftp = fold(type);
        q.strTyp = ftp.startsWith("cadde") ? "cadde" : ftp.startsWith("sokak") || ftp.startsWith("sokag") ? "sokak"
                : ftp.startsWith("bulvar") ? "bulvar" : ftp.startsWith("yolu") ? "yol"
                : ftp.startsWith("cikma") ? "cikmaz" : ftp.startsWith("meydan") ? "meydan" : "";
        q.no = number;
        return q;
    }

    /** Müşterinin "|" sonrası tarifinden temiz bir arama yazısı; sokak/cadde yoksa null. */
    private static String tarifText(ArrayList<String> rt, ArrayList<String> name,
                                    ArrayList<String> mahalle, String district) {
        if (rt.isEmpty()) {
            return null;
        }
        ArrayList<String> c = new ArrayList<String>();
        int typeAt = -1;
        for (int i = 0; i < rt.size(); i++) {
            String t = rt.get(i);
            String f = fold(t);
            if (CUT.contains(f)) {
                // "ikizler sitesi", "A Blok" gibi site/blok adını da at
                if (typeAt >= 0 && c.size() - 1 > typeAt
                        && !DIGITS.matcher(c.get(c.size() - 1)).matches()
                        && !c.get(c.size() - 1).equals("No")) {
                    c.remove(c.size() - 1);
                }
                // "Blok 45/7" -> No 45
                if ((f.equals("blok") || f.equals("blogu")) && typeAt >= 0 && i + 1 < rt.size()
                        && !c.contains("No")) {
                    String nx = rt.get(i + 1);
                    Matcher bs = SLASH_NUM.matcher(nx);
                    String dg = bs.matches() ? bs.group(1) : (DIGITS.matcher(nx).matches() ? nx : null);
                    if (dg != null) {
                        c.add("No");
                        c.add(dg);
                    }
                }
                break;
            }
            Matcher mn = NO_COLON.matcher(f);
            if (mn.matches()) {
                c.add("No");
                c.add(mn.group(1));
                if (typeAt >= 0) {
                    break;
                }
                continue;
            }
            if (f.equals("no") && i + 1 < rt.size()) {
                String nx = rt.get(i + 1);
                Matcher ms = SLASH_NUM.matcher(nx);
                String digits = ms.matches() ? ms.group(1) : (DIGITS.matcher(nx).matches() ? nx : null);
                if (digits != null) {
                    c.add("No");
                    c.add(digits);
                    i++;
                    if (typeAt >= 0) {
                        break;
                    }
                    continue;
                }
            }
            Matcher ms2 = SLASH_NUM.matcher(t);
            if (ms2.matches()) {
                t = ms2.group(1);
            }
            c.add(t);
            if (typeAt < 0 && isType(t)) {
                typeAt = c.size() - 1;
            }
        }
        if (typeAt < 0) {
            return null;
        }
        // sondaki ilçe adlarını at (sonra tekrar eklenecek)
        while (!c.isEmpty()) {
            String f = fold(c.get(c.size() - 1));
            if (f.equals("bandirma") || f.equals("balikesir")) {
                c.remove(c.size() - 1);
            } else {
                break;
            }
        }
        if (c.size() < 2) {
            return null;
        }
        // bitişik yazılan sokak adını ana adresteki ayrık haliyle değiştir
        if (!name.isEmpty()) {
            String nk = key(join(name));
            for (int w = 1; w <= 4; w++) {
                for (int j = 0; j + w <= c.size(); j++) {
                    if (w == name.size() && key(join(c.subList(j, j + w))).equals(nk)) {
                        continue;
                    }
                    if (key(join(c.subList(j, j + w))).equals(nk)) {
                        for (int r = 0; r < w; r++) {
                            c.remove(j);
                        }
                        c.addAll(j, name);
                        w = 5;
                        break;
                    }
                }
            }
        }
        boolean hasMah = false;
        for (String t : c) {
            if (t.equals("Mahallesi")) {
                hasMah = true;
                break;
            }
        }
        ArrayList<String> out = new ArrayList<String>();
        if (!hasMah) {
            out.addAll(mahalle);
        }
        out.addAll(c);
        return join(out) + " " + district;
    }

    // ------------------------------------------------------------------ kayıtlı teslimat konumları

    /** İki nokta arası metre ([lat, lon] dizileri). */
    public static double dist(double[] a, double[] b) {
        double r = 6371000.0;
        double p1 = Math.toRadians(a[0]);
        double p2 = Math.toRadians(b[0]);
        double dp = p2 - p1;
        double dl = Math.toRadians(b[1] - a[1]);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /**
     * Kayıtlı noktaların "ortası" (eskiden yeniye sıralı liste). 1-2 noktada en yenisi,
     * 3 ve fazlada diğerlerine en yakın nokta; böylece yanlış basılan tek bir teslimat sonucu bozmaz.
     */
    public static double[] medoid(java.util.List<double[]> pts) {
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        if (pts.size() <= 2) {
            return pts.get(pts.size() - 1);
        }
        double[] best = null;
        double bestSum = Double.MAX_VALUE;
        for (int i = pts.size() - 1; i >= 0; i--) {
            double sum = 0;
            for (int j = 0; j < pts.size(); j++) {
                if (i != j) {
                    sum += dist(pts.get(i), pts.get(j));
                }
            }
            if (sum < bestSum) {
                bestSum = sum;
                best = pts.get(i);
            }
        }
        return best;
    }

    /** Yandex Navi'de koordinata rota kurar. Başarılıysa true. */
    public static boolean openCoord(Context ctx, double lat, double lon, boolean fresh) {
        String la = String.format(Locale.US, "%.6f", lat);
        String lo = String.format(Locale.US, "%.6f", lon);
        String[] uris = {
                "yandexnavi://build_route_on_map?lat_to=" + la + "&lon_to=" + lo,
                "yandexmaps://maps.yandex.com/?rtext=~" + la + "," + lo + "&rtt=auto",
                "geo:" + la + "," + lo + "?q=" + la + "," + lo
        };
        for (String u : uris) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (fresh) {
                    // Navi önceki konumda takılı kalıyorsa: eski ekranı kapatıp yeni adresle baştan aç
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                }
                ctx.startActivity(i);
                return true;
            } catch (ActivityNotFoundException e) {
                // bir sonrakini dene
            } catch (Exception e) {
                // bir sonrakini dene
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Yandex'i aç

    /** Yandex Navi'yi aranan yazıyla açar. Başarılıysa true. */
    public static boolean open(Context ctx, String query, boolean fresh) {
        String enc;
        try {
            enc = URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            return false;
        }
        String[] uris = {
                "yandexnavi://map_search?text=" + enc,
                "yandexmaps://maps.yandex.ru/?text=" + enc,
                "geo:0,0?q=" + enc
        };
        for (String u : uris) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (fresh) {
                    // Navi önceki konumda takılı kalıyorsa: eski ekranı kapatıp yeni adresle baştan aç
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                }
                ctx.startActivity(i);
                return true;
            } catch (ActivityNotFoundException e) {
                // bir sonrakini dene
            } catch (Exception e) {
                // bir sonrakini dene
            }
        }
        return false;
    }
}
