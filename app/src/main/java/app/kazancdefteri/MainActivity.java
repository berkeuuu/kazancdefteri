package app.kazancdefteri;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReaderService.dismissOverlay();
        if (web != null) {
            web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("reader", MODE_PRIVATE);
    }

    private boolean serviceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        return enabled.contains(getPackageName() + "/") && enabled.contains("ReaderService");
    }

    private String findMigpack() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : list) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).replace(" ", "");
            String pkg = ri.activityInfo.packageName;
            if (label.contains("migpack") || pkg.toLowerCase(Locale.ROOT).contains("migpack")) {
                return pkg;
            }
        }
        return null;
    }

    public class Bridge {

        @JavascriptInterface
        public boolean serviceEnabled() {
            return serviceOn();
        }

        @JavascriptInterface
        public boolean isNight() {
            int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            });
        }

        /** Okumayı başlatır ve Migpack'i açar. Dönüş: ok, no_service, no_migpack */
        @JavascriptInterface
        public String startRead() {
            if (!serviceOn()) {
                return "no_service";
            }
            SharedPreferences p = prefs();
            String pkg = p.getString("mig_pkg", null);
            if (pkg == null || getPackageManager().getLaunchIntentForPackage(pkg) == null) {
                pkg = findMigpack();
                if (pkg == null) {
                    return "no_migpack";
                }
                p.edit().putString("mig_pkg", pkg).apply();
            }
            final Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                return "no_migpack";
            }
            long nowMs = System.currentTimeMillis();
            p.edit()
                    .putLong("armed_until", nowMs + 6 * 60 * 1000L)
                    .putString("run_id", String.valueOf(nowMs))
                    .putString("crawl", "")
                    .putString("snaps", "[]")
                    .apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startActivity(launch);
                }
            });
            return "ok";
        }

        /** Telefondaki uygulamaları listeler (Migpack adıyla bulunamazsa elle seçmek için). */
        @JavascriptInterface
        public String listApps() {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN);
                q.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
                final ArrayList<String[]> items = new ArrayList<String[]>();
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) {
                        continue;
                    }
                    items.add(new String[]{String.valueOf(ri.loadLabel(pm)), pkg});
                }
                Collections.sort(items, new Comparator<String[]>() {
                    @Override
                    public int compare(String[] a, String[] b) {
                        return a[0].compareToIgnoreCase(b[0]);
                    }
                });
                for (String[] it : items) {
                    JSONObject o = new JSONObject();
                    o.put("l", it[0]);
                    o.put("p", it[1]);
                    arr.put(o);
                }
            } catch (Exception e) {
                // boş liste dönsün
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void setMigPkg(String pkg) {
            if (pkg != null && pkg.length() > 0) {
                prefs().edit().putString("mig_pkg", pkg).apply();
            }
        }

        /** Okunan ekranlar: {"armed":true/false,"crawl":"done"|"","snaps":[[satırlar],[satırlar],...]} */
        @JavascriptInterface
        public String getDump() {
            SharedPreferences p = prefs();
            JSONObject o = new JSONObject();
            try {
                o.put("armed", System.currentTimeMillis() < p.getLong("armed_until", 0L));
                o.put("crawl", p.getString("crawl", ""));
                o.put("snaps", new JSONArray(p.getString("snaps", "[]")));
            } catch (Exception e) {
                // boş sonuç dönsün
            }
            return o.toString();
        }

        @JavascriptInterface
        public void stopRead() {
            prefs().edit().putLong("armed_until", 0L).apply();
        }

        @JavascriptInterface
        public void clearDump() {
            prefs().edit().putString("snaps", "[]").putString("crawl", "").putLong("armed_until", 0L).apply();
        }

        @JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Kazanç Defteri", text));
                    Toast.makeText(MainActivity.this, "Kopyalandı", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
