package app.kazancdefteri;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
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
            "^\\d{1,2}\\s+(Ocak|Şubat|Mart|Nisan|Mayıs|Haziran|Temmuz|Ağustos|Eylül|Ekim|Kasım|Aralık)\\s+\\p{L}+(,\\s*[\\p{L}\\p{N}_]+)?$",
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

    private static ReaderService instance;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (Exception e) {
                // bir sonraki turda tekrar dene
            }
            handler.postDelayed(this, 1400);
        }
    };

    private WindowManager wm;
    private FrameLayout overlay;
    private WindowManager.LayoutParams overlayLp;
    private ProgressBar ovSpin;
    private TextView ovTick;
    private TextView ovTitle;
    private TextView ovSub;
    private Button ovBtn;

    private final HashSet<String> done = new HashSet<String>();
    private String runId = "";
    private int actions = 0;
    private int idle = 0;
    private int lastDone = 0;
    private boolean finished = false;
    private boolean announced = false;
    private boolean overlayDone = false;
    private boolean clicked = false;
    private int clicks = 0;
    private int clickTick = 0;
    private int tail = 0;
    private String lastSig = "";
    private long runStart = 0L;

    /** Gün başlığının adı: "2 Ekim Cuma, 40" veya "2 Ekim Cuma, chevron_down" -> "2 Ekim Cuma". */
    private static String headKey(String t) {
        int c = t.indexOf(',');
        return (c >= 0 ? t.substring(0, c) : t).trim();
    }

    /** Kart açıldığında görünen satırlar (yeni sürüm: "Aktif çalışma süresi", eski sürüm: "Paketler"). */
    private static boolean isDetail(String t) {
        String l = t.toLowerCase(java.util.Locale.ROOT);
        return l.startsWith("paketler") || l.startsWith("aktif çal") || l.startsWith("aktif cal")
                || l.startsWith("kay\u0131p s") || l.startsWith("kayıp s");
    }

    /** Kart açık mı? Açıkken başlıkta paket sayısı görünür ("1 Ekim Perşembe, 37") ya da detay satırları vardır. */
    private static boolean isExpanded(Item h, ArrayList<Item> items, int limit) {
        if (Pattern.compile(",\\s*\\d+$").matcher(h.text).find()) {
            return true;
        }
        for (Item it : items) {
            if (it.top > h.top && it.top < limit && isDetail(it.text)) {
                return true;
            }
        }
        return false;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("reader", MODE_PRIVATE);
    }

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1400);
        handler.removeCallbacks(navTicker);
        handler.postDelayed(navTicker, 900);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(navTicker);
        hideNav();
        removeOverlay();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(navTicker);
        hideNav();
        removeOverlay();
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // İş, düzenli çalışan zamanlayıcıyla yapılıyor (ticker).
    }

    @Override
    public void onInterrupt() {
    }

    public static boolean isAlive() {
        return instance != null;
    }

    /** Uygulama öne gelince çağrılır: okuma ekranını kaldırır. */
    public static void dismissOverlay() {
        final ReaderService s = instance;
        if (s != null) {
            s.handler.post(new Runnable() {
                @Override
                public void run() {
                    s.removeOverlay();
                }
            });
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showOverlay() {
        if (overlay != null) {
            return;
        }
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlay = new FrameLayout(this);
            overlay.setBackgroundColor(0xAA000000);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(24), dp(28), dp(24), dp(24));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(20));
            card.setBackground(bg);

            ovSpin = new ProgressBar(this);
            card.addView(ovSpin, new LinearLayout.LayoutParams(dp(56), dp(56)));

            ovTick = new TextView(this);
            ovTick.setText("\u2714");
            ovTick.setTextSize(30);
            ovTick.setTextColor(Color.WHITE);
            ovTick.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xFF2E9E5B);
            ovTick.setBackground(circle);
            ovTick.setVisibility(View.GONE);
            card.addView(ovTick, new LinearLayout.LayoutParams(dp(64), dp(64)));

            ovTitle = new TextView(this);
            ovTitle.setTextSize(19);
            ovTitle.setTextColor(0xFF1B1B1B);
            ovTitle.setGravity(Gravity.CENTER);
            ovTitle.setPadding(0, dp(16), 0, dp(4));
            card.addView(ovTitle);

            ovSub = new TextView(this);
            ovSub.setTextSize(14);
            ovSub.setTextColor(0xFF555555);
            ovSub.setGravity(Gravity.CENTER);
            card.addView(ovSub);

            ovBtn = new Button(this);
            ovBtn.setText("Tamam");
            ovBtn.setTextColor(Color.WHITE);
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(0xFF2E9E5B);
            bb.setCornerRadius(dp(12));
            ovBtn.setBackground(bb);
            ovBtn.setVisibility(View.GONE);
            ovBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeOverlay();
                    Intent i = new Intent(ReaderService.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Kazanç Defteri'ni elle aç.");
                    }
                }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
            blp.topMargin = dp(20);
            card.addView(ovBtn, blp);

            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            clp.leftMargin = dp(28);
            clp.rightMargin = dp(28);
            overlay.addView(card, clp);

            overlayLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            wm.addView(overlay, overlayLp);
        } catch (Exception e) {
            overlay = null;
        }
    }

    private void setOverlay(boolean finishedOk, String title, String sub) {
        overlayDone = finishedOk;
        showOverlay();
        if (overlay == null) {
            return;
        }
        ovTitle.setText(title);
        ovSub.setText(sub);
        ovSpin.setVisibility(finishedOk ? View.GONE : View.VISIBLE);
        ovTick.setVisibility(finishedOk ? View.VISIBLE : View.GONE);
        ovBtn.setVisibility(finishedOk ? View.VISIBLE : View.GONE);
        boolean touchable = (overlayLp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
        if (finishedOk == touchable) {
            return;
        }
        try {
            if (finishedOk) {
                overlayLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                overlayLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            wm.updateViewLayout(overlay, overlayLp);
        } catch (Exception e) {
            // önemli değil
        }
    }

    private void removeOverlay() {
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception e) {
                // zaten kalkmış
            }
            overlay = null;
        }
    }

    /** Başlık satırı olarak tıklanabilen öğe (içindeki yazı parçaları değil). */
    private static boolean isHeadNode(Item it) {
        AccessibilityNodeInfo c = it.node;
        for (int g = 0; c != null && g < 3; g++) {
            if (c.isClickable()) {
                return true;
            }
            c = c.getParent();
        }
        return false;
    }

    private void step() {
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        if (now > p.getLong("armed_until", 0L)) {
            if (!overlayDone) {
                removeOverlay();
            }
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
            overlayDone = false;
            clicked = false;
            clicks = 0;
            clickTick = 0;
            tail = 0;
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
            if (!overlayDone) {
                removeOverlay();
            }
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
            if (HEADER.matcher(it.text).matches() && isHeadNode(it)) {
                boolean dup = false;
                for (Item h0 : headers) {
                    if (headKey(h0.text).equals(headKey(it.text))) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    headers.add(it);
                }
            }
        }
        if (finished) {
            return;
        }
        if (headers.isEmpty()) {
            setOverlay(false, "Okuma için hazır",
                    "Migpack'te Geçmiş Siparişlerim > Günlük sekmesini aç. Günler görünce okuma başlayacak.");
            return;
        }
        boolean todayMode = "today".equals(p.getString("mode", "all"));
        if (todayMode) {
            setOverlay(false, "Bugün okunuyor, lütfen bekleyin", "Telefona dokunma.");
            handleToday(p, root, headers, items, now);
            return;
        }
        setOverlay(false, "Okuma yapılıyor, lütfen bekleyin",
                done.size() + " gün okundu. Telefona dokunma.");

        // Hangi gün kartı açık, hangisi kapalı?
        Item target = null;
        for (int i = 0; i < headers.size(); i++) {
            Item h = headers.get(i);
            int limit = (i + 1 < headers.size()) ? headers.get(i + 1).top : Integer.MAX_VALUE;
            boolean expanded = isExpanded(h, items, limit);
            if (expanded) {
                done.add(headKey(h.text));
            } else if (target == null && !done.contains(headKey(h.text))) {
                target = h;
            }
        }

        if (actions >= MAX_ACTIONS || now - runStart > MAX_MS) {
            finish(p);
            return;
        }

        if (target != null) {
            click(target.node);
            done.add(headKey(target.text));
            actions++;
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
            return;
        }
        finish(p);
    }

    /** Sadece bugünün gün kartını bulur, açar, içeriğini görünür hale getirir ve bitirir. */
    private void handleToday(SharedPreferences p, AccessibilityNodeInfo root, ArrayList<Item> headers,
                             ArrayList<Item> items, long now) {
        if (actions >= MAX_ACTIONS || now - runStart > 90 * 1000L) {
            finish(p);
            return;
        }
        Pattern pat = Pattern.compile(p.getString("today_pat", "^$"),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Item th = null;
        int idx = -1;
        for (int i = 0; i < headers.size(); i++) {
            if (pat.matcher(headers.get(i).text).matches()) {
                th = headers.get(i);
                idx = i;
                break;
            }
        }
        if (th == null) {
            if (scrollForward(root)) {
                actions++;
                return;
            }
            finish(p);
            return;
        }
        int limit = (idx + 1 < headers.size()) ? headers.get(idx + 1).top : Integer.MAX_VALUE;
        boolean expanded = isExpanded(th, items, limit);
        if (!expanded) {
            if (clicks >= 2 && actions - clickTick >= 3) {
                finish(p);
                return;
            }
            if (!clicked || actions - clickTick >= 3) {
                clicks++;
                click(th.node);
                clicked = true;
                clickTick = actions;
            }
            actions++;
            return;
        }
        if (tail < 3 && scrollForward(root)) {
            tail++;
            actions++;
            return;
        }
        finish(p);
    }

    private void finish(SharedPreferences p) {
        finished = true;
        p.edit().putString("crawl", "done").putLong("armed_until", 0L).apply();
        if ("today".equals(p.getString("mode", "all"))) {
            setOverlay(true, "Okuma tamamlandı",
                    "Bugünün verisi okundu. Tamam'a basınca Kazanç Defteri'ne aktarılacak.");
        } else {
            setOverlay(true, "Okuma tamamlandı",
                    done.size() + " gün okundu. Tamam'a basınca veriler Kazanç Defteri'ne aktarılacak.");
        }
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
        walk(n, out, depth, 200);
    }

    private void walk(AccessibilityNodeInfo n, ArrayList<Item> out, int depth, int maxLen) {
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
                if (s.length() > 0 && s.length() < maxLen) {
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
                walk(ch, out, depth + 1, maxLen);
            }
        }
    }

    // ---------------------------------------------------------------------
    // "Navi'ye Git" düğmesi: Migpack > Görev Detayı ekranında belirir.
    // Sadece Migpack'in ekranına bakar; adres telefondan çıkmaz.
    // ---------------------------------------------------------------------

    private final Runnable navTicker = new Runnable() {
        @Override
        public void run() {
            try {
                navStep();
            } catch (Exception e) {
                // bir sonraki turda tekrar dene
            }
            handler.postDelayed(this, 900);
        }
    };

    private Button navBtn;
    private WindowManager.LayoutParams navLp;

    private boolean isMigpackPkg(String pkg) {
        try {
            if (pkg.toLowerCase(java.util.Locale.ROOT).contains("migpack")) {
                return true;
            }
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0));
            return label != null
                    && label.toString().toLowerCase(java.util.Locale.ROOT).replace(" ", "").contains("migpack");
        } catch (Exception e) {
            return false;
        }
    }

    /** Ekrandaki Teslimat Adresi yazısı; Görev Detayı ekranında değilsek null. */
    private String readAddress(AccessibilityNodeInfo root) {
        ArrayList<Item> items = new ArrayList<Item>();
        walk(root, items, 0, 600);
        if (items.isEmpty()) {
            return null;
        }
        Collections.sort(items, BY_TOP);
        Item head = null;
        for (Item it : items) {
            if (it.text.equalsIgnoreCase("Teslimat Adresi")) {
                head = it;
                break;
            }
        }
        if (head == null) {
            return null;
        }
        for (Item it : items) {
            // Yeşil ok düğmesinin gizli etiketi ("last-mile-...-button") gibi yazıları atla,
            // sadece gerçekten adrese benzeyen satırı al.
            if (it.top >= head.top && NavHelper.looksLikeAddress(it.text)) {
                return it.text;
            }
        }
        return null;
    }

    private void navStep() {
        if (overlay != null) {
            // okuma ekranı açıkken düğme görünmesin
            hideNav();
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        CharSequence rp = root.getPackageName();
        if (rp == null) {
            return;
        }
        String pkg = rp.toString();
        SharedPreferences p = prefs();
        String mig = p.getString("mig_pkg", "");
        if (mig.length() == 0) {
            if (isMigpackPkg(pkg)) {
                p.edit().putString("mig_pkg", pkg).apply();
                mig = pkg;
            }
        }
        if (!pkg.equals(mig)) {
            hideNav();
            return;
        }
        String addr = readAddress(root);
        if (addr != null) {
            lastAddr = addr;
            lastAddrAt = System.currentTimeMillis();
        } else if (isDeliveredScreen(root)) {
            recordDelivery();
        }
        if (addr == null || NavHelper.parse(addr) == null) {
            hideNav();
            return;
        }
        showNav();
    }

    // --- Teslim edilen binanın konumunu kaydet ---
    private String lastAddr = null;
    private long lastAddrAt = 0L;
    private long lastSavedAt = -1L;
    private boolean permToast = false;

    /** Migpack'te "Sipariş ... teslim edildi" tikli ekranı (Göreve Dön / Mola Ver) mı? */
    private boolean isDeliveredScreen(AccessibilityNodeInfo root) {
        ArrayList<Item> items = new ArrayList<Item>();
        walk(root, items, 0, 300);
        boolean done = false;
        boolean back = false;
        for (Item it : items) {
            String f = NavHelper.fold(it.text);
            if (f.contains("teslim edildi")) {
                done = true;
            }
            if (f.contains("goreve don")) {
                back = true;
            }
        }
        return done && back;
    }

    private void recordDelivery() {
        long now = System.currentTimeMillis();
        if (lastAddr == null || now - lastAddrAt > 60000L || lastSavedAt == lastAddrAt) {
            return;
        }
        lastSavedAt = lastAddrAt;
        NavHelper.Query q = NavHelper.parse(lastAddr);
        if (q == null || q.bkey.length() == 0) {
            return;
        }
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!permToast) {
                permToast = true;
                toast("Konum izni yok. Kazanç Defteri > Migpack sekmesinden aç.");
            }
            return;
        }
        final String bkey = q.bkey;
        grabLocation(new LocCb() {
            @Override
            public void got(android.location.Location l) {
                if (l == null) {
                    toast("Teslimat konumu alınamadı.");
                    return;
                }
                if (saveLocation(bkey, l)) {
                    toast("Teslimat konumu kaydedildi.");
                }
            }
        });
    }

    private interface LocCb {
        void got(android.location.Location l);
    }

    /** Son 90 saniyede alınmış, yeterince doğru bir konum varsa onu; yoksa yeni konum ister. */
    private void grabLocation(final LocCb cb) {
        final android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            cb.got(null);
            return;
        }
        android.location.Location best = null;
        try {
            for (String pv : lm.getProviders(true)) {
                android.location.Location l = null;
                try {
                    l = lm.getLastKnownLocation(pv);
                } catch (SecurityException se) {
                    l = null;
                }
                if (l == null) {
                    continue;
                }
                long ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000L;
                if (ageMs > 90000L || l.getAccuracy() > 60f) {
                    continue;
                }
                if (best == null || l.getAccuracy() < best.getAccuracy()) {
                    best = l;
                }
            }
        } catch (Exception e) {
            best = null;
        }
        if (best != null) {
            cb.got(best);
            return;
        }
        String pv = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                ? android.location.LocationManager.GPS_PROVIDER
                : android.location.LocationManager.NETWORK_PROVIDER;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(pv, null, new java.util.concurrent.Executor() {
                    @Override
                    public void execute(Runnable r) {
                        handler.post(r);
                    }
                }, new java.util.function.Consumer<android.location.Location>() {
                    @Override
                    public void accept(android.location.Location l) {
                        cb.got(l);
                    }
                });
            } else {
                lm.requestSingleUpdate(pv, new android.location.LocationListener() {
                    @Override
                    public void onLocationChanged(android.location.Location l) {
                        cb.got(l);
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
                    }

                    @Override
                    public void onProviderEnabled(String provider) {
                    }

                    @Override
                    public void onProviderDisabled(String provider) {
                    }
                }, Looper.getMainLooper());
            }
        } catch (Exception e) {
            cb.got(null);
        }
    }

    /** Binanın kayıtlı noktalarına ekler (en fazla 5). */
    private boolean saveLocation(String bkey, android.location.Location l) {
        try {
            SharedPreferences np = getSharedPreferences("nav", MODE_PRIVATE);
            JSONArray arr = new JSONArray(np.getString("loc_" + bkey, "[]"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("la", l.getLatitude());
            o.put("lo", l.getLongitude());
            o.put("ac", (double) l.getAccuracy());
            o.put("t", System.currentTimeMillis());
            // Aykırı nokta koruması: binanın en az 2 kaydı varsa ve yeni nokta 150 m'den uzaksa hemen kabul etme;
            // ikinci bir teslimat da aynı yeri (100 m içinde) gösterirse bina gerçekten oradadır, kayıtlar yenilenir.
            if (arr.length() >= 2) {
                ArrayList<double[]> old = new ArrayList<double[]>();
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject p0 = arr.getJSONObject(i);
                    old.add(new double[]{p0.getDouble("la"), p0.getDouble("lo")});
                }
                double[] now = new double[]{l.getLatitude(), l.getLongitude()};
                if (NavHelper.dist(NavHelper.medoid(old), now) > 150.0) {
                    String pk = "pend_" + bkey;
                    org.json.JSONObject pend = null;
                    try {
                        pend = new org.json.JSONObject(np.getString(pk, "null"));
                    } catch (Exception e) {
                        pend = null;
                    }
                    if (pend != null && NavHelper.dist(new double[]{pend.getDouble("la"), pend.getDouble("lo")}, now) <= 100.0) {
                        JSONArray fresh = new JSONArray();
                        fresh.put(pend);
                        fresh.put(o);
                        np.edit().putString("loc_" + bkey, fresh.toString()).remove(pk).apply();
                    } else {
                        np.edit().putString(pk, o.toString()).apply();
                    }
                    return true;
                }
                np.edit().remove("pend_" + bkey).apply();
            }
            arr.put(o);
            if (arr.length() > 5) {
                JSONArray cut = new JSONArray();
                for (int i = arr.length() - 5; i < arr.length(); i++) {
                    cut.put(arr.get(i));
                }
                arr = cut;
            }
            np.edit().putString("loc_" + bkey, arr.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Binanın kayıtlı noktalarının ortası: {lat, lon, nokta sayısı}; yoksa null. */
    private double[] loadCoord(String bkey) {
        try {
            JSONArray arr = new JSONArray(getSharedPreferences("nav", MODE_PRIVATE)
                    .getString("loc_" + bkey, "[]"));
            ArrayList<double[]> pts = new ArrayList<double[]>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                pts.add(new double[]{o.getDouble("la"), o.getDouble("lo")});
            }
            double[] m = NavHelper.medoid(pts);
            return m == null ? null : new double[]{m[0], m[1], pts.size()};
        } catch (Exception e) {
            return null;
        }
    }

    private void showNav() {
        if (navBtn != null) {
            return;
        }
        try {
            if (wm == null) {
                wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
            navBtn = new Button(this);
            navBtn.setText("Navi'ye Git ➤");
            navBtn.setAllCaps(false);
            navBtn.setTextSize(15);
            navBtn.setTextColor(0xFF1B1B1B);
            navBtn.setPadding(dp(18), dp(8), dp(18), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFFC400);
            bg.setCornerRadius(dp(26));
            bg.setStroke(dp(2), 0xFF1B1B1B);
            navBtn.setBackground(bg);
            navBtn.setElevation(dp(6));
            navBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goNavi(false);
                }
            });
            navBtn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    goNavi(true);
                    return true;
                }
            });
            navLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            navLp.gravity = Gravity.BOTTOM | Gravity.END;
            navLp.x = dp(12);
            navLp.y = dp(175);
            wm.addView(navBtn, navLp);
        } catch (Exception e) {
            navBtn = null;
        }
    }

    private void hideNav() {
        if (navBtn != null) {
            try {
                wm.removeView(navBtn);
            } catch (Exception e) {
                // zaten kalkmış
            }
            navBtn = null;
        }
    }

    // --- Navi'ye Git: hangi yolun işe yaradığını hatırlar, zor adresleri biriktirir ---
    private String lastOpenAddr = "";
    private long lastOpenAt = 0L;
    private String navAddr = "";
    private int navStart = 0;
    private int navLong = 0;
    private String pendKey = null;
    private char pendKind = 'A';
    private final Runnable navCommit = new Runnable() {
        @Override
        public void run() {
            commitLearn();
        }
    };

    /** Son açılan yol 2 dakika içinde tekrar denenmediyse "işe yaradı" sayılır ve bu sokak için hatırlanır. */
    private void commitLearn() {
        handler.removeCallbacks(navCommit);
        if (pendKey != null && pendKey.length() > 0) {
            getSharedPreferences("nav", MODE_PRIVATE).edit()
                    .putString("k_" + pendKey, String.valueOf(pendKind)).apply();
        }
        pendKey = null;
    }

    private void logHard(String addr, NavHelper.Query q, int idx) {
        try {
            SharedPreferences np = getSharedPreferences("nav", MODE_PRIVATE);
            JSONArray all = new JSONArray(np.getString("log", "[]"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("adres", addr);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < q.texts.size(); i++) {
                sb.append(q.kinds.get(i)).append(": ").append(q.texts.get(i)).append("\n");
            }
            o.put("yollar", sb.toString().trim());
            o.put("denenen", String.valueOf(q.kinds.get(idx)));
            all.put(o);
            if (all.length() > 60) {
                JSONArray cut = new JSONArray();
                for (int i = all.length() - 60; i < all.length(); i++) {
                    cut.put(all.get(i));
                }
                all = cut;
            }
            np.edit().putString("log", all.toString()).apply();
        } catch (Exception e) {
            // kaydedilemediyse önemli değil
        }
    }

    /**
     * Kısa basış: bu sokakta daha önce işe yarayan yol (yoksa A: sokak+no).
     * Uzun basış: her seferinde sıradaki başka yol (B mahalleli, C müşterinin tarifi ...).
     */
    private void goNavi(boolean longPress) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String addr = root == null ? null : readAddress(root);
        NavHelper.Query q = NavHelper.parse(addr);
        if (q == null || q.texts.isEmpty()) {
            toast("Adres okunamadı.");
            return;
        }
        final double[] kc = q.bkey.length() > 0 ? loadCoord(q.bkey) : null;
        if (kc != null) {
            q.texts.add(0, "Kayıtlı konum (" + (int) kc[2] + " teslimat)");
            q.kinds.add(0, Character.valueOf('K'));
        }
        int n = q.texts.size();
        if (!addr.equals(navAddr)) {
            if (pendKey != null) {
                commitLearn();
            }
            navAddr = addr;
            navLong = 0;
            navStart = 0;
            String lk = kc != null ? "" : getSharedPreferences("nav", MODE_PRIVATE).getString("k_" + q.key, "");
            if (lk.length() == 1) {
                int ix = q.kinds.indexOf(Character.valueOf(lk.charAt(0)));
                if (ix >= 0) {
                    navStart = ix;
                }
            }
        }
        int idx = navStart;
        if (longPress) {
            if (n < 2) {
                toast("Başka yol yok, aynı adres açılıyor.");
            } else {
                navLong++;
                int k = (navLong - 1) % (n - 1);
                idx = k >= navStart ? k + 1 : k;
            }
        } else {
            navLong = 0;
        }
        String text = q.texts.get(idx);
        // Aynı adreste 3 dakika içinde tekrar basılıyorsa Navi önceki konumda takılmış olabilir: baştan aç
        long nowMs = System.currentTimeMillis();
        boolean fresh = addr.equals(lastOpenAddr) && nowMs - lastOpenAt < 180000L;
        lastOpenAddr = addr;
        lastOpenAt = nowMs;
        boolean opened;
        if (q.kinds.get(idx).charValue() == 'K' && kc != null) {
            opened = NavHelper.openCoord(getApplicationContext(), kc[0], kc[1], fresh);
        } else {
            opened = NavHelper.open(getApplicationContext(), text, fresh);
        }
        if (!opened) {
            toast("Yandex Navi açılamadı.");
            return;
        }
        pendKey = q.key;
        pendKind = q.kinds.get(idx).charValue();
        handler.removeCallbacks(navCommit);
        handler.postDelayed(navCommit, 120000L);
        if (idx != navStart || longPress) {
            logHard(addr, q, idx);
        }
        toast((idx + 1) + "/" + n + ": " + text);
    }
}
