package com.sms.sync;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_READ_SMS = 100;
    private static final int REQ_OTHER = 101;
    private static final String SMS_URI = "content://sms";

    private static MainActivity instance;
    private TextView tvStatus;

    private EditText etServer;
    private TextView tvCount;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        setContentView(R.layout.activity_main);

        Pref.applyDefaults(this);

        etServer = findViewById(R.id.etServer);
        tvStatus = findViewById(R.id.tvStatus);
        tvCount = findViewById(R.id.tvCount);
        etServer.setText(Pref.server(this));

        Button btnPerm = findViewById(R.id.btnPerm);
        Button btnSync = findViewById(R.id.btnSync);
        Button btnService = findViewById(R.id.btnService);

        btnPerm.setOnClickListener(v -> requestPermissions());
        btnSync.setOnClickListener(v -> startSync());
        if (btnService != null) {
            btnService.setOnClickListener(v -> toggleService());
        }
        requestPermissions();
    }

    /** 给 Service 的自动/指令上传结果回显到界面 */
    public static void notifyStatus(String s) {
        MainActivity m = instance;
        if (m == null) return;
        m.runOnUiThread(() -> {
            TextView t = m.findViewById(R.id.tvStatus);
            if (t != null) t.setText("状态：" + s);
        });
    }

    private void requestPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_SMS);
        if (Build.VERSION.SDK_INT >= 30) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            setStatus("已拥有所需权限");
            return;
        }
        ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_OTHER);
        setStatus("请授予读取短信 / 通知权限…");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean all = true;
        for (int g : grantResults) if (g != PackageManager.PERMISSION_GRANTED) all = false;
        setStatus(all ? "已拥有所需权限" : "部分权限被拒，功能受限");
    }

    private void toggleService() {
        Intent i = new Intent(this, SyncService.class);
        if (ServiceState.isRunning(this)) {
            stopService(i);
            setStatus("已停止后台监听");
        } else {
            ContextCompat.startForegroundService(this, i);
            setStatus("已开启后台监听：连到 " + Pref.ssid(this) + " 或网页点「立即上传」时自动同步");
        }
    }

    private void startSync() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            setStatus("请先授予读取短信权限");
            return;
        }
        executor.execute(this::doSync);
    }

    private void doSync() {
        setStatus("正在读取并上传…");
        try {
            List<SmsItem> items = readSms();
            int uploaded = upload(items);
            setCount(uploaded);
            setStatus("完成，成功上传 " + uploaded + " 条");
        } catch (Exception e) {
            setStatus("失败：" + e.getMessage());
        }
    }

    private List<SmsItem> readSms() {
        List<SmsItem> list = new ArrayList<>();
        Cursor c = getContentResolver().query(Uri.parse(SMS_URI),
                new String[]{"_id","address","body","date","type"}, null, null, null);
        if (c == null) throw new IllegalStateException("无法访问短信数据库");
        while (c.moveToNext()) {
            String a = c.getString(1);
            if ("unknown".equals(a)) a = "";
            list.add(new SmsItem(c.getInt(0), a==null?"":a.trim(),
                    c.getString(2)==null?"":c.getString(2), c.getLong(3), c.getInt(4)));
        }
        c.close();
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
        String resp = readAll(conn);
        conn.disconnect();
        if (code / 100 == 2) return items.size();
        throw new IllegalStateException("服务器返回 " + code + "：" + resp);
    }

    private String readAll(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder();
        BufferedReader r;
        try { r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)); }
        catch (Exception e) {
            try { r = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8)); }
            catch (Exception e2) { return ""; }
        }
        String l;
        try { while ((l = r.readLine()) != null) sb.append(l); } catch (Exception ignored) {}
        return sb.toString();
    }

    private void setCount(int n) { runOnUiThread(() -> tvCount.setText("共上传 " + n + " 条")); }
    private void setStatus(String s) { runOnUiThread(() -> tvStatus.setText("状态：" + s)); }

    @Override protected void onDestroy() {
        instance = null;
        executor.shutdownNow();
        super.onDestroy();
    }
}
