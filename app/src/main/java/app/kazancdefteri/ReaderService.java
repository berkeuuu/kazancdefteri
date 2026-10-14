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
    private String lastSig = "";
    private long runStart = 0L;

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
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        removeOverlay();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(ticker);
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
            if (HEADER.matcher(it.text).matches()) {
                headers.add(it);
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
        setOverlay(false, "Okuma yapılıyor, lütfen bekleyin",
                done.size() + " gün okundu. Telefona dokunma.");

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

    private void finish(SharedPreferences p) {
        finished = true;
        p.edit().putString("crawl", "done").putLong("armed_until", 0L).apply();
        setOverlay(true, "Okuma tamamlandı",
                done.size() + " gün okundu. Tamam'a basınca veriler Kazanç Defteri'ne aktarılacak.");
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
