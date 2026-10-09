package com.sms.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 前台常驻服务：
 *  - 轮询服务器 /command，网页点「立即上传」后 App 自动读短信并上传
 *  - 检测到手机连到指定 WiFi(perpower_5G) 时自动读短信并上传
 */
public class SyncService extends Service {

    private static final String TARGET_SSID = "perpower_5G";
    private static final String CHANNEL_ID = "smsync_channel";
    private static final int NOTIF_ID = 100;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollTask;
    private final BroadcastReceiver wifiRcv = new WifiCmdReceiver();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        if (scheduler == null) {
            scheduler = Executors.newScheduledThreadPool(2);
            startPolling();
        }
        // 注册 WiFi/网络变化监听
        try {
            IntentFilter f = new IntentFilter();
            f.addAction("android.net.wifi.STATE_CHANGE");
            f.addAction("android.net.wifi.WIFI_STATE_CHANGED");
            f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(wifiRcv, f);
        } catch (Exception ignored) {}
        // 立即检查一次当前 WiFi，命中就同步
        scheduler.execute(this::maybeAutoSyncByWifi);
        return START_STICKY;
    }

    private void startPolling() {
        pollTask = scheduler.scheduleWithFixedDelay(this::pollCommand, 3, 5, TimeUnit.SECONDS);
    }

    /** 轮询服务器指令队列；网页点了「立即上传」就触发一次同步 */
    private void pollCommand() {
        try {
            String url = Pref.server(this) + "command";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("X-Sms-Key", Pref.key(this));
            if (conn.getResponseCode() / 100 != 2) { conn.disconnect(); return; }
            String resp = readAll(conn.getInputStream());
            conn.disconnect();
            if (resp != null && resp.contains("\"upload\"")) {
                scheduler.execute(this::doSync);
            }
        } catch (Exception ignored) {}
    }

    /** 当前是否连到 TARGET_SSID，是则同步 */
    private void maybeAutoSyncByWifi() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) return;
        if (currentSsid().equalsIgnoreCase(TARGET_SSID)) {
            doSync();
        }
    }

    private String currentSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String s = info.getSSID();
            if (s == null) return "";
            s = s.trim();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
            if ("0".equals(s) || "unknown".equalsIgnoreCase(s)) {
                // 读不到 SSID（未给定位权限），用网络接口名兜底判断不可靠，直接返回空
                return "";
            }
            return s;
        } catch (Exception e) { return ""; }
    }

    private void doSync() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            List<SmsItem> items = readSms();
            int n = upload(items);
            MainActivity.notifyStatus("自动/指令触发：成功上传 " + n + " 条");
        } catch (Exception e) {
            MainActivity.notifyStatus("自动上传失败：" + e.getMessage());
        }
    }

    private List<SmsItem> readSms() {
        java.util.ArrayList<SmsItem> list = new java.util.ArrayList<>();
        Cursor c = getContentResolver().query(Uri.parse("content://sms"),
                new String[]{"_id","address","body","date","type"}, null, null, null);
        if (c != null) {
            while (c.moveToNext()) {
                String addr = c.getString(1);
                if ("unknown".equals(addr)) addr = "";
                list.add(new SmsItem(c.getInt(0), addr==null?"":addr.trim(),
                        c.getString(2)==null?"":c.getString(2),
                        c.getLong(3), c.getInt(4)));
            }
            c.close();
        }
        return list;
    }

    private int upload(List<SmsItem> items) throws Exception {
        String url = Pref.server(this) + "upload";
        String json = Pref.buildJson(this, items);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("X-Sms-Key", Pref.key(this));
        conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();
        readAll2(conn);
        conn.disconnect();
        if (code / 100 != 2) throw new IllegalStateException("服务器返回 " + code);
        return items.size();
    }

    private static String readAll(InputStream is) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) sb.append(l);
        } catch (Exception ignored) {}
        return sb.toString();
    }
    private static void readAll2(HttpURLConnection conn) {
        try (InputStream is = conn.getInputStream()) {
            byte[] b = new byte[4096]; int n; while ((n = is.read(b)) != -1) {}
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "短信同步服务",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("短信同步")
                .setContentText("监听中：连到 " + TARGET_SSID + " 或网页指令时自动上传")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setOngoing(true)
                .setShowWhen(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        try { unregisterReceiver(wifiRcv); } catch (Exception ignored) {}
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    /** 连到指定 WiFi 就触发一次同步 */
    private class WifiCmdReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            scheduler.schedule(SyncService.this::maybeAutoSyncByWifi, 2, TimeUnit.SECONDS);
        }
    }
}