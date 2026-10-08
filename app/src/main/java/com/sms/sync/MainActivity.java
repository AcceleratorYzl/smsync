package com.sms.sync;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
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
    private static final String SMS_URI = "content://sms";

    private EditText etServer;
    private TextView tvStatus, tvCount;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServer = findViewById(R.id.etServer);
        tvStatus = findViewById(R.id.tvStatus);
        tvCount = findViewById(R.id.tvCount);

        Button btnPerm = findViewById(R.id.btnPerm);
        Button btnSync = findViewById(R.id.btnSync);

        btnPerm.setOnClickListener(v -> requestReadSms());
        btnSync.setOnClickListener(v -> startSync());
    }

    private void setStatus(String s) {
        runOnUiThread(() -> tvStatus.setText("状态：" + s));
    }

    private void setCount(int n) {
        runOnUiThread(() -> tvCount.setText("共上传 " + n + " 条"));
    }

    private void requestReadSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            setStatus("已拥有读取短信权限");
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_SMS}, REQ_READ_SMS);
        setStatus("请授予读取短信权限…");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ_SMS) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            setStatus(ok ? "已拥有读取短信权限" : "权限被拒绝，无法读取短信");
        }
    }

    private void startSync() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            setStatus("请先申请读取短信权限");
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
        Cursor c = getContentResolver().query(
                Uri.parse(SMS_URI),
                new String[]{"_id", "address", "body", "date", "type"},
                null, null, null);
        if (c == null) {
            throw new IllegalStateException("无法访问短信数据库，请确认已授权");
        }
        while (c.moveToNext()) {
            int id = c.getInt(0);
            String address = c.getString(1);
            String body = c.getString(2);
            long date = c.getLong(3);
            int type = c.getInt(4);
            if ("unknown".equals(address)) {
                address = "";
            }
            list.add(new SmsItem(id, address == null ? "" : address.trim(),
                    body == null ? "" : body, date, type));
        }
        c.close();
        return list;
    }

    private int upload(List<SmsItem> items) throws Exception {
        String server = etServer.getText().toString().trim();
        if (server.isEmpty()) {
            server = "http://192.168.1.100:8080";
        }
        if (!server.endsWith("/")) {
            server = server + "/";
        }
        String url = server.replaceFirst("/+$", "/") + "upload";
        String json = buildJson(items);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        int code = conn.getResponseCode();
        String resp = readAll(conn);
        conn.disconnect();
        if (code >= 200 && code < 300) {
            return items.size();
        } else {
            throw new IllegalStateException("服务器返回 " + code + "：" + resp);
        }
    }

    private String buildJson(List<SmsItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"messages\":[");
        for (int i = 0; i < items.size(); i++) {
            SmsItem it = items.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(it.id)
              .append(",\"address\":\"").append(esc(it.address))
              .append("\",\"body\":\"").append(esc(it.body))
              .append("\",\"date\":").append(it.date)
              .append(",\"type\":").append(it.type).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String readAll(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder();
        BufferedReader r;
        try {
            r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            try {
                r = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            } catch (Exception e2) {
                return "";
            }
        }
        String line;
        try {
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static class SmsItem {
        final int id;
        final String address;
        final String body;
        final long date;
        final int type;

        SmsItem(int id, String address, String body, long date, int type) {
            this.id = id;
            this.address = address;
            this.body = body;
            this.date = date;
            this.type = type;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
