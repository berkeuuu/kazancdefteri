package app.kazancdefteri;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Sadece kullanıcı "Migpack'ten oku" düğmesine bastıktan sonraki 5 dakika boyunca
 * ve sadece Migpack uygulamasının ekranında çalışır.
 *
 * Geçmiş Siparişlerim > Günlük ekranında kapalı gün kartlarını tek tek açar,
 * aşağı kaydırır ve her ekranda görünen yazıları ayrı bir "ekran görüntüsü" olarak saklar.
 * Veri telefonda kalır, internete gönderilmez (uygulamada internet izni yok).
 */
public class ReaderService extends AccessibilityService {

    /** "01 Ekim Perşembe" gibi gün başlıkları */
    private static final Pattern HEADER = Pattern.compile(
            "^\\d{1,2}\\s+(Ocak|Şubat|Mart|Nisan|Mayıs|Haziran|Temmuz|Ağustos|Eylül|Ekim|Kasım|Aralık)\\s+\\p{L}+$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final int MAX_ACTIONS = 150;
    private static final long MAX_MS = 4 * 60 * 1000L;

    private static class Item {
        int top;
        int left;
        String text;
        AccessibilityNodeInfo node;
    }

    private static final Comparator<Item> BY_TOP = new Comparator<Item>() {
        @Override
        public int compare(Item a, Item b) {
            return a.top - b.top;
        }
    };

    private static final Comparator<Item> BY_LEFT = new Comparator<Item>() {
        @Override
        public int compare(Item a, Item b) {
            return a.left - b.left;
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepRunnable = new Runnable() {
        @Override
        public void run() {
            step();
        }
    };

    private final HashSet<String> done = new HashSet<String>();
    private String runId = "";
    private int actions = 0;
    private int idle = 0;
    private int lastDone = 0;
    private boolean finished = false;
    private boolean announced = false;
    private String lastSig = "";
    private long runStart = 0L;

    private SharedPreferences prefs() {
        return getSharedPreferences("reader", MODE_PRIVATE);
    }

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        SharedPreferences p = prefs();
        if (System.currentTimeMillis() > p.getLong("armed_until", 0L)) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        String mig = p.getString("mig_pkg", "");
        if (pkg == null || mig.length() == 0 || !mig.equals(pkg.toString())) {
            return;
        }
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, 900);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(stepRunnable);
    }

    private void step() {
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        if (now > p.getLong("armed_until", 0L)) {
            return;
        }

        String rid = p.getString("run_id", "");
        if (!rid.equals(runId)) {
            runId = rid;
            done.clear();
            actions = 0;
            idle = 0;
            lastDone = 0;
            finished = false;
            announced = false;
            lastSig = "";
            runStart = now;
        }

        String mig = p.getString("mig_pkg", "");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        CharSequence rp = root.getPackageName();
        if (rp == null || !mig.equals(rp.toString())) {
            return;
        }

        ArrayList<Item> items = new ArrayList<Item>();
        walk(root, items, 0);
        if (items.isEmpty()) {
            return;
        }
        Collections.sort(items, BY_TOP);
        saveSnapshot(p, buildRows(items));

        ArrayList<Item> headers = new ArrayList<Item>();
        for (Item it : items) {
            if (HEADER.matcher(it.text).matches()) {
                headers.add(it);
            }
        }
        if (headers.isEmpty() || finished) {
            return;
        }

        if (!announced) {
            announced = true;
            toast("Kazanç Defteri günleri okuyor, bekle...");
        }

        // Hangi gün kartı açık, hangisi kapalı?
        Item target = null;
        for (int i = 0; i < headers.size(); i++) {
            Item h = headers.get(i);
            int limit = (i + 1 < headers.size()) ? headers.get(i + 1).top : Integer.MAX_VALUE;
            boolean expanded = false;
            for (Item it : items) {
                if (it.top > h.top && it.top < limit && it.text.startsWith("Paketler")) {
                    expanded = true;
                    break;
                }
            }
            if (expanded) {
                done.add(h.text);
            } else if (target == null && !done.contains(h.text)) {
                target = h;
            }
        }

        if (actions >= MAX_ACTIONS || now - runStart > MAX_MS) {
            finish(p);
            return;
        }

        if (target != null) {
            click(target.node);
            done.add(target.text);
            actions++;
            handler.postDelayed(stepRunnable, 1500);
            return;
        }

        // Görünen bütün günler açıldı: aşağı kaydır
        if (done.size() == lastDone) {
            idle++;
        } else {
            idle = 0;
        }
        lastDone = done.size();
        if (idle >= 2) {
            finish(p);
            return;
        }
        if (scrollForward(root)) {
            actions++;
            handler.postDelayed(stepRunnable, 1500);
            return;
        }
        finish(p);
    }

    private void finish(SharedPreferences p) {
        finished = true;
        p.edit().putString("crawl", "done").apply();
        toast("Okuma bitti: " + done.size() + " gün. Kazanç Defteri'ne dön.");
    }

    private void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        int guard = 0;
        while (c != null && !c.isClickable() && guard < 6) {
            c = c.getParent();
            guard++;
        }
        if (c == null) {
            c = n;
        }
        c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean scrollForward(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> list = new ArrayList<AccessibilityNodeInfo>();
        collectScrollable(root, list, 0);
        AccessibilityNodeInfo best = null;
        long bestArea = -1;
        Rect r = new Rect();
        for (AccessibilityNodeInfo s : list) {
            s.getBoundsInScreen(r);
            long area = (long) r.width() * (long) r.height();
            if (area > bestArea) {
                bestArea = area;
                best = s;
            }
        }
        if (best == null) {
            return false;
        }
        return best.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private void collectScrollable(AccessibilityNodeInfo n, ArrayList<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 50) {
            return;
        }
        if (n.isScrollable()) {
            out.add(n);
        }
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) {
                collectScrollable(ch, out, depth + 1);
            }
        }
    }

    /** Yukarıdan aşağı sıralı öğeleri satırlara çevirir; aynı satırdakiler " | " ile birleşir. */
    private ArrayList<String> buildRows(ArrayList<Item> sortedByTop) {
        ArrayList<String> rows = new ArrayList<String>();
        int i = 0;
        while (i < sortedByTop.size()) {
            int rowTop = sortedByTop.get(i).top;
            ArrayList<Item> group = new ArrayList<Item>();
            while (i < sortedByTop.size() && sortedByTop.get(i).top - rowTop <= 16) {
                group.add(sortedByTop.get(i));
                i++;
            }
            Collections.sort(group, BY_LEFT);
            StringBuilder sb = new StringBuilder();
            String last = null;
            for (Item it : group) {
                if (it.text.equals(last)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(it.text);
                last = it.text;
            }
            if (sb.length() > 0) {
                rows.add(sb.toString());
            }
        }
        return rows;
    }

    /** Her ekranı ayrı kaydeder (gün başlığı ile içeriği birbirine karışmasın diye). */
    private void saveSnapshot(SharedPreferences p, ArrayList<String> rows) {
        String sig = rows.toString();
        if (sig.equals(lastSig)) {
            return;
        }
        lastSig = sig;
        try {
            JSONArray all = new JSONArray(p.getString("snaps", "[]"));
            JSONArray one = new JSONArray();
            for (String r : rows) {
                one.put(r);
            }
            all.put(one);
            if (all.length() > 80) {
                JSONArray cut = new JSONArray();
                for (int i = all.length() - 80; i < all.length(); i++) {
                    cut.put(all.get(i));
                }
                all = cut;
            }
            p.edit().putString("snaps", all.toString()).apply();
        } catch (Exception e) {
            // kaydedilemediyse bu ekranı atla
        }
    }

    private void walk(AccessibilityNodeInfo n, ArrayList<Item> out, int depth) {
        if (n == null || depth > 50 || out.size() > 1500) {
            return;
        }
        if (n.isVisibleToUser()) {
            CharSequence t = n.getText();
            if (t == null || t.length() == 0) {
                t = n.getContentDescription();
            }
            if (t != null) {
                String s = t.toString().trim();
                if (s.length() > 0 && s.length() < 200) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    Item it = new Item();
                    it.top = r.top;
                    it.left = r.left;
                    it.text = s;
                    it.node = n;
                    out.add(it);
                }
            }
        }
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) {
                walk(ch, out, depth + 1);
            }
        }
    }
}
