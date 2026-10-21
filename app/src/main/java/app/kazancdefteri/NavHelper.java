package app.kazancdefteri;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migpack "Görev Detayı" ekranındaki Teslimat Adresi yazısını Yandex Navigasyon'un
 * anlayacağı bir arama yazısına çevirir ve Yandex Navi'yi açar.
 *
 * Örnek giriş:
 *   "Yeni Mh. İsmail Balcıoğlu Sokak Bina No:6 Kat:2 Daire No:9 Bandırma/Balıkesir | Atik inşaat köşe bina"
 * Ana arama:
 *   "Yeni Mahallesi İsmail Balcıoğlu Sokak No 6 Bandırma Balıkesir"
 * Yedek arama (müşterinin "|" işaretinden sonra yazdığı tarif cadde/sokak içeriyorsa):
 *   "... cadde/sokak ... Bandırma Balıkesir"
 */
public final class NavHelper {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern BINA = Pattern.compile("\\bBina\\s*No\\s*:?\\s*([\\p{L}\\p{N}/\\-]+)", FLAGS);
    private static final Pattern KAT_DAIRE = Pattern.compile("\\b(Kat|Daire)\\s*(No)?\\s*:?\\s*[\\p{L}\\p{N}/\\-]*", FLAGS);
    private static final Pattern ILCE = Pattern.compile("([\\p{L}]+)\\s*/\\s*([\\p{L}]+)\\s*$");
    private static final Pattern MAH = Pattern.compile("\\bMh\\.?(?=\\s|$)", FLAGS);
    private static final Pattern CUT_TARIF = Pattern.compile("\\b(daire|d\\s*:|kat|apt|apartman|ap\\.).*$", FLAGS);
    private static final Pattern STREET_WORD = Pattern.compile("\\b(cadde|caddesi|cad\\.?|sokak|sk\\.?|bulvar|bulvarı|mahalle|mahallesi)\\b", FLAGS);

    private NavHelper() {
    }

    /** Ana ve yedek arama yazısı. alt boş olabilir. */
    public static final class Query {
        public String main;
        public String alt;
    }

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
        Matcher mb = BINA.matcher(left);
        if (mb.find()) {
            number = mb.group(1);
            left = left.substring(0, mb.start()).trim();
        }
        left = KAT_DAIRE.matcher(left).replaceAll("").trim();
        left = MAH.matcher(left).replaceAll("Mahallesi").replaceAll("\\s+", " ").trim();
        if (left.length() == 0) {
            return null;
        }

        Query q = new Query();
        q.main = left + (number.length() > 0 ? " No " + number : "") + " " + district;

        if (right.length() > 0 && STREET_WORD.matcher(right).find()) {
            String r = CUT_TARIF.matcher(right).replaceAll("").replaceAll("\\s+", " ").trim();
            if (r.length() >= 6) {
                q.alt = r + " " + district;
            }
        }
        return q;
    }

    /** Yandex Navi'yi aranan yazıyla açar. Başarılıysa true. */
    public static boolean open(Context ctx, String query) {
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
