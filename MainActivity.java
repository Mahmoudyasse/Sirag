package com.webapp.shell;

import android.Manifest;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
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

    // [NEW] reference used by the media service to send play/pause/next/prev back to the page
    static MainActivity inst;

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
        inst = this; // [NEW]

        // Create the notification channel at startup so it shows up in system settings
        Notifier.createChannel(this);

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
                WebResourceResponse res = loader.shouldInterceptRequest(r.getUrl());
                if (res != null) return res;
                return proxyCors(r);
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

        ensureNotifications();
    }

    // ---------------- Notifications: permission + settings ----------------
    private void ensureNotifications() {
        if (Notifier.enabled(this)) { Notifier.welcomeOnce(this); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        } else {
            askOpenNotifSettings(false);
        }
    }

    private void askOpenNotifSettings(boolean force) {
        if (isFinishing()) return;
        SharedPreferences sp = getSharedPreferences("siraj_ui", MODE_PRIVATE);
        int n = sp.getInt("asked", 0);
        if (!force && n >= 3) return;
        sp.edit().putInt("asked", n + 1).apply();
        new AlertDialog.Builder(this)
            .setTitle("الإشعارات مقفولة")
            .setMessage("فعّل الإشعارات عشان توصلك تنبيهات سراج.")
            .setPositiveButton("فتح الإعدادات", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { openNotifSettings(); }
            })
            .setNegativeButton("لاحقاً", null)
            .show();
    }

    private void openNotifSettings() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            }
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e2) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Notifier.enabled(this)) Notifier.welcomeOnce(this);
    }

    // ---------------- CORS proxy for cross-origin fetch/XHR ----------------
    // The page is served from https://appassets.androidplatform.net, so fetch()
    // to other sites (audio downloads, APIs) is blocked unless the remote server
    // sends CORS headers. We fetch those requests natively and add the headers.
    private WebResourceResponse proxyCors(WebResourceRequest r) {
        try {
            if (!"GET".equalsIgnoreCase(r.getMethod())) return null;
            Uri u = r.getUrl();
            String scheme = u.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            if ("appassets.androidplatform.net".equals(u.getHost())) return null;

            Map<String, String> h = r.getRequestHeaders();
            String origin = null;
            for (Map.Entry<String, String> e : h.entrySet()) {
                if ("Origin".equalsIgnoreCase(e.getKey())) origin = e.getValue();
            }
            if (origin == null) return null; // not a CORS request, let WebView handle it

            String url = u.toString();
            HttpURLConnection c = null;
            for (int i = 0; i < 6; i++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                for (Map.Entry<String, String> e : h.entrySet()) {
                    String k = e.getKey();
                    if ("Origin".equalsIgnoreCase(k) || "Referer".equalsIgnoreCase(k)
                        || "Host".equalsIgnoreCase(k) || "Accept-Encoding".equalsIgnoreCase(k)) continue;
                    c.setRequestProperty(k, e.getValue());
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) break;
                    url = new URL(new URL(url), loc).toString();
                    c.disconnect();
                    continue;
                }
                break;
            }

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                // let WebView see the real error
                c.disconnect();
                return null;
            }
            String ct = c.getContentType();
            String mime = "application/octet-stream";
            String enc = null;
            if (ct != null) {
                mime = ct.split(";")[0].trim();
                int ci = ct.toLowerCase().indexOf("charset=");
                if (ci >= 0) enc = ct.substring(ci + 8).trim();
            }
            Map<String, String> rh = new HashMap<String, String>();
            rh.put("Access-Control-Allow-Origin", origin);
            rh.put("Access-Control-Allow-Credentials", "true");
            rh.put("Access-Control-Allow-Headers", "*");
            rh.put("Access-Control-Expose-Headers", "*");
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) rh.put("Content-Range", cr);
            String ar = c.getHeaderField("Accept-Ranges");
            if (ar != null) rh.put("Accept-Ranges", ar);

            String reason = c.getResponseMessage();
            if (reason == null || reason.length() == 0) reason = "OK";
            InputStream in = c.getInputStream();
            return new WebResourceResponse(mime, enc, code, reason, rh, in);
        } catch (Exception e) {
            return null;
        }
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
            if (ok) Notifier.welcomeOnce(this); else askOpenNotifSettings(false);
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

    // [NEW] Back button: never exits the app. The page decides what to close
    // (mushaf, sheets, drawer, inner pages); on the 5 main tabs nothing happens.
    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustomView(); return; }
        web.evaluateJavascript(
            "(function(){try{return window.sirajBack?String(window.sirajBack()):'none';}catch(e){return 'none';}})()",
            new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) { }
            });
    }

    @Override protected void onPause() { super.onPause(); }

    @Override protected void onDestroy() {
        if (inst == this) inst = null; // [NEW]
        if (web != null) { ((ViewGroup) web.getParent()).removeView(web); web.destroy(); }
        super.onDestroy();
    }

    // [NEW] run JS on the page from any thread
    void runJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web != null) web.evaluateJavascript(js, null);
            }
        });
    }

    // ================================================================
    // JS <-> native bridge (window.SirajNative)
    // ================================================================
    public class Bridge {
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

        // [NEW] playback state from the page -> lock screen / notification controls
        @JavascriptInterface
        public void media(String title, String artist, boolean playing, double posMs, double durMs, String color) {
            try {
                if (!playing && !MediaService.running) return;
                Intent i = new Intent(MainActivity.this, MediaService.class);
                i.setAction("siraj.UPDATE");
                i.putExtra("t", title);
                i.putExtra("a", artist);
                i.putExtra("p", playing);
                i.putExtra("pos", (long) posMs);
                i.putExtra("dur", (long) durMs);
                i.putExtra("c", color);
                if (Build.VERSION.SDK_INT >= 26 && !MediaService.running) startForegroundService(i);
                else startService(i);
            } catch (Exception e) { }
        }

        // [NEW]
        @JavascriptInterface
        public void mediaStop() {
            try { stopService(new Intent(MainActivity.this, MediaService.class)); } catch (Exception e) { }
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33 && !Notifier.enabled(MainActivity.this)) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
                    } else {
                        boolean on = Notifier.enabled(MainActivity.this);
                        if (!on) askOpenNotifSettings(true);
                        web.evaluateJavascript(
                            "window.__sirajPerm&&window.__sirajPerm('" + (on ? "granted" : "denied") + "')", null);
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

        static void welcomeOnce(Context c) {
            SharedPreferences sp = c.getSharedPreferences("siraj_ui", Context.MODE_PRIVATE);
            if (sp.getBoolean("welcomed", false)) return;
            sp.edit().putBoolean("welcomed", true).apply();
            show(c, 1, "سراج", "تم تفعيل الإشعارات بنجاح");
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

    // ================================================================
    // [NEW] Media service: lock-screen + notification-shade playback
    // controls (play / pause / next / previous / seek) for the audio
    // that plays inside the page. Keeps playback alive in background.
    // ================================================================
    public static class MediaService extends Service {
        static final String CH = "siraj_media";
        static final int NID = 7001;
        static boolean running = false;

        private MediaSession ms;
        private Bitmap art;
        private boolean fg = false;
        private String title = "سراج", artist = "";
        private boolean playing = false;
        private long pos = -1, dur = 0;
        private int color = 0xFF14B8A6;

        @Override public IBinder onBind(Intent i) { return null; }

        @Override
        public void onCreate() {
            super.onCreate();
            running = true;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(CH, "تشغيل سراج", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
            art = makeArt();
            ms = new MediaSession(this, "siraj");
            if (Build.VERSION.SDK_INT < 26) {
                ms.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            }
            ms.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { toPage("play", 0); }
                @Override public void onPause() { toPage("pause", 0); }
                @Override public void onSkipToNext() { toPage("next", 0); }
                @Override public void onSkipToPrevious() { toPage("prev", 0); }
                @Override public void onSeekTo(long p) { toPage("seek", p); }
            });
            ms.setActive(true);
        }

        private static void toPage(String action, long v) {
            MainActivity m = MainActivity.inst;
            if (m == null) return;
            m.runJs("window.sirajMedia&&sirajMedia('" + action + "'," + v + ")");
        }

        @Override
        public int onStartCommand(Intent i, int flags, int startId) {
            String a = i == null ? null : i.getAction();
            if (a == null) { stopSelf(); return START_NOT_STICKY; }
            if ("siraj.PLAY".equals(a)) { toPage("play", 0); }
            else if ("siraj.PAUSE".equals(a)) { toPage("pause", 0); }
            else if ("siraj.NEXT".equals(a)) { toPage("next", 0); }
            else if ("siraj.PREV".equals(a)) { toPage("prev", 0); }
            else if ("siraj.STOP".equals(a)) {
                toPage("pause", 0);
                fg = false;
                stopForeground(true);
                stopSelf();
            } else if ("siraj.UPDATE".equals(a)) {
                String t = i.getStringExtra("t");
                String ar = i.getStringExtra("a");
                title = (t == null || t.length() == 0) ? "سراج" : t;
                artist = ar == null ? "" : ar;
                playing = i.getBooleanExtra("p", false);
                pos = i.getLongExtra("pos", -1);
                dur = i.getLongExtra("dur", 0);
                try { color = Color.parseColor(i.getStringExtra("c")); } catch (Exception e) { }
                refresh();
            }
            return START_NOT_STICKY;
        }

        private void refresh() {
            PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                    pos >= 0 ? pos : PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    playing ? 1f : 0f, SystemClock.elapsedRealtime());
            ms.setPlaybackState(pb.build());

            MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "سراج")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            if (dur > 0) md.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
            ms.setMetadata(md.build());

            Notification n = buildNotif();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            try {
                if (playing) {
                    if (!fg) {
                        if (Build.VERSION.SDK_INT >= 29) {
                            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                        } else {
                            startForeground(NID, n);
                        }
                        fg = true;
                    } else {
                        nm.notify(NID, n);
                    }
                } else {
                    if (!fg) {
                        // satisfy startForegroundService() contract, then drop to a normal notification
                        if (Build.VERSION.SDK_INT >= 29) {
                            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                        } else {
                            startForeground(NID, n);
                        }
                    }
                    stopForeground(false);
                    fg = false;
                    nm.notify(NID, n);
                }
            } catch (Exception e) {
                try { nm.notify(NID, n); } catch (Exception e2) { }
            }
        }

        private PendingIntent act(String action, int code) {
            Intent i = new Intent(this, MediaService.class);
            i.setAction(action);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getService(this, code, i, flags);
        }

        private Notification buildNotif() {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent content = PendingIntent.getActivity(this, 7002, open, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
            b.setSmallIcon(Notifier.icon(this))
             .setContentTitle(title)
             .setContentText(artist)
             .setLargeIcon(art)
             .setContentIntent(content)
             .setDeleteIntent(act("siraj.STOP", 3))
             .setOngoing(playing)
             .setShowWhen(false)
             .setColor(color)
             .setVisibility(Notification.VISIBILITY_PUBLIC);
            if (Build.VERSION.SDK_INT >= 26) b.setColorized(true);

            b.addAction(android.R.drawable.ic_media_previous, "السابق", act("siraj.PREV", 0));
            if (playing) {
                b.addAction(android.R.drawable.ic_media_pause, "إيقاف مؤقت", act("siraj.PAUSE", 1));
            } else {
                b.addAction(android.R.drawable.ic_media_play, "تشغيل", act("siraj.PLAY", 1));
            }
            b.addAction(android.R.drawable.ic_media_next, "التالي", act("siraj.NEXT", 2));

            b.setStyle(new Notification.MediaStyle()
                .setMediaSession(ms.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
            return b.build();
        }

        // Cover art drawn in code: dark teal glass-like gradient + gold/teal "سراج"
        private Bitmap makeArt() {
            int s = 512;
            Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, s, s, 0xFF0F5F58, 0xFF04140F, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, s, s, p);
            p.setShader(new android.graphics.RadialGradient(s * 0.5f, s * 0.42f, s * 0.46f,
                0x662DD4BF, 0x002DD4BF, Shader.TileMode.CLAMP));
            c.drawCircle(s * 0.5f, s * 0.42f, s * 0.46f, p);
            p.setShader(new LinearGradient(0, s * 0.3f, s, s * 0.7f, 0xFFE8C06A, 0xFF5EEAD4, Shader.TileMode.CLAMP));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(180);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("سراج", s / 2f, s * 0.58f, p);
            p.setShader(null);
            p.setColor(0xCCFFFFFF);
            p.setTextSize(44);
            c.drawText("S I R A J", s / 2f, s * 0.76f, p);
            return bm;
        }

        @Override
        public void onDestroy() {
            running = false;
            try { ms.setActive(false); ms.release(); } catch (Exception e) { }
            super.onDestroy();
        }
    }
}
