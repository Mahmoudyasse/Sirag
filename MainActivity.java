package com.webapp.shell;

import android.Manifest;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.Map;

public class MainActivity extends Activity {

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private WebChromeClient chromeClient;
    private ValueCallback<Uri[]> fileCb;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;
    private static final int REQ_FILE = 11, REQ_LOC = 12, REQ_NOTIF = 13;

    // ---------------------------------------------------------------
    // JS injected into the page: makes the normal web Notification API
    // (new Notification(...), Notification.requestPermission(), 
    // registration.showNotification(...)) work through the native bridge.
    // Extra helpers: sirajSchedule(tag, title, body, timestampMillis)
    //                sirajCancel(tag)
    // ---------------------------------------------------------------
    private static final String NOTIF_POLYFILL =
        "(function(){"
        + "if(window.__sirajNotif||!window.SirajNative)return;window.__sirajNotif=true;"
        + "var S=window.SirajNative;"
        + "function N(t,o){o=o||{};"
        +   "S.notify(String(o.tag||('n'+Date.now())),String(t),String(o.body||''));"
        +   "this.close=function(){};}"
        + "Object.defineProperty(N,'permission',{get:function(){return S.hasPermission()?'granted':'default';}});"
        + "N.requestPermission=function(cb){return new Promise(function(res){"
        +   "if(S.hasPermission()){if(cb)cb('granted');res('granted');return;}"
        +   "window.__sirajPerm=function(r){if(cb)cb(r);res(r);};"
        +   "S.requestPermission();});};"
        + "window.Notification=N;"
        + "try{ServiceWorkerRegistration.prototype.showNotification=function(t,o){new N(t,o);return Promise.resolve();};}catch(e){}"
        + "window.sirajSchedule=function(tag,title,body,at){S.schedule(String(tag),String(title),String(body),Number(at));};"
        + "window.sirajCancel=function(tag){S.cancel(String(tag));};"
        + "})();";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Create the notification channel at startup so it shows up in system settings
        Notifier.createChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.enabled(this)) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#04140F"));
        setContentView(root);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#04140F"));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "SirajNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                super.onPageStarted(v, url, icon);
                v.evaluateJavascript(NOTIF_POLYFILL, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                v.evaluateJavascript(NOTIF_POLYFILL, null);
            }
        });

        chromeClient = new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    geoCb = cb; geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) { r.deny(); }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v; customCb = cb;
                root.addView(v, new FrameLayout.LayoutParams(-1, -1));
                web.setVisibility(View.GONE);
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customCb != null) customCb.onCustomViewHidden();
                web.setVisibility(View.VISIBLE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        };
        web.setWebChromeClient(chromeClient);

        if (b != null) web.restoreState(b);
        else web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC && geoCb != null) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            geoCb.invoke(geoOrigin, ok, false);
            geoCb = null;
        }
        if (code == REQ_NOTIF && web != null) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            web.evaluateJavascript(
                "window.__sirajPerm&&window.__sirajPerm('" + (ok ? "granted" : "denied") + "')", null);
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    r = new Uri[n];
                    for (int i = 0; i < n; i++) r[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    r = new Uri[]{data.getData()};
                }
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        web.saveState(b);
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCb != null) {
            customCb.onCustomViewHidden();
            customCb = null;
        }
        web.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustomView(); return; }
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); }

    @Override protected void onDestroy() {
        if (web != null) { ((ViewGroup) web.getParent()).removeView(web); web.destroy(); }
        super.onDestroy();
    }

    // ================================================================
    // JS <-> native bridge (window.SirajNative)
    // ================================================================
    private class Bridge {
        @JavascriptInterface
        public void notify(String tag, String title, String body) {
            Notifier.show(MainActivity.this, Notifier.idOf(tag), title, body);
        }

        @JavascriptInterface
        public void schedule(String tag, String title, String body, double atMillis) {
            Notifier.schedule(MainActivity.this, Notifier.idOf(tag), title, body, (long) atMillis);
        }

        @JavascriptInterface
        public void cancel(String tag) {
            Notifier.cancel(MainActivity.this, Notifier.idOf(tag));
        }

        @JavascriptInterface
        public void cancelAll() {
            Notifier.cancelAll(MainActivity.this);
        }

        @JavascriptInterface
        public boolean hasPermission() {
            return Notifier.enabled(MainActivity.this);
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33 && !Notifier.enabled(MainActivity.this)) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
                    } else {
                        web.evaluateJavascript(
                            "window.__sirajPerm&&window.__sirajPerm('" + (Notifier.enabled(MainActivity.this) ? "granted" : "denied") + "')", null);
                    }
                }
            });
        }
    }

    // ================================================================
    // Notification helper: channel, show, schedule, cancel
    // ================================================================
    static final class Notifier {
        static final String CHANNEL_ID = "siraj_default";
        static final String PREFS = "siraj_alarms";

        static int idOf(String tag) {
            return (tag == null ? 0 : tag.hashCode()) & 0x7fffffff;
        }

        static void createChannel(Context c) {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "تنبيهات سراج", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("تنبيهات وتذكيرات تطبيق سراج");
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }

        static boolean enabled(Context c) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 24) return nm.areNotificationsEnabled();
            return true;
        }

        static int icon(Context c) {
            int id = c.getResources().getIdentifier("ic_launcher", "drawable", c.getPackageName());
            return id != 0 ? id : android.R.drawable.ic_popup_reminder;
        }

        static void show(Context c, int id, String title, String body) {
            if (!enabled(c)) return;
            createChannel(c);

            Intent i = new Intent(c, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, id, i, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL_ID)
                : new Notification.Builder(c);
            b.setSmallIcon(icon(c))
             .setContentTitle(title)
             .setContentText(body)
             .setStyle(new Notification.BigTextStyle().bigText(body))
             .setContentIntent(pi)
             .setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_HIGH);
                b.setDefaults(Notification.DEFAULT_ALL);
            }

            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(id, b.build());
        }

        private static PendingIntent alarmIntent(Context c, int id, String title, String body) {
            Intent i = new Intent(c, AlarmReceiver.class);
            i.putExtra("id", id);
            i.putExtra("title", title);
            i.putExtra("body", body);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getBroadcast(c, id, i, flags);
        }

        static void schedule(Context c, int id, String title, String body, long at) {
            if (at <= System.currentTimeMillis()) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = alarmIntent(c, id, title, body);

            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }

            try {
                JSONObject o = new JSONObject();
                o.put("id", id).put("title", title).put("body", body).put("at", at);
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(String.valueOf(id), o.toString()).apply();
            } catch (Exception e) { }
        }

        static void cancel(Context c, int id) {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(alarmIntent(c, id, "", ""));
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(String.valueOf(id)).apply();
        }

        static void cancelAll(Context c) {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (String k : sp.getAll().keySet()) {
                try { cancel(c, Integer.parseInt(k)); } catch (Exception e) { }
            }
            sp.edit().clear().apply();
        }

        static void rescheduleAll(Context c) {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                try {
                    JSONObject o = new JSONObject(String.valueOf(e.getValue()));
                    long at = o.getLong("at");
                    if (at > System.currentTimeMillis()) {
                        schedule(c, o.getInt("id"), o.getString("title"), o.getString("body"), at);
                    } else {
                        sp.edit().remove(e.getKey()).apply();
                    }
                } catch (Exception ex) { }
            }
        }
    }

    // Fires when a scheduled notification is due
    public static class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int id = i.getIntExtra("id", 0);
            Notifier.show(c, id, i.getStringExtra("title"), i.getStringExtra("body"));
            c.getSharedPreferences(Notifier.PREFS, Context.MODE_PRIVATE)
                .edit().remove(String.valueOf(id)).apply();
        }
    }

    // Re-arms scheduled notifications after a reboot
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
                Notifier.rescheduleAll(c);
            }
        }
    }
}
