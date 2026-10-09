package com.sms.sync;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地偏好：服务器地址 + 共享密钥，并构建上传 JSON */
public final class Pref {

    private static final String PREFS = "smsync";
    private static final String K_SERVER = "server";
    private static final String K_KEY = "key";
    private static final String K_SSID = "ssid";

    private Pref() {}

    public static String server(Context c) {
        String s = prefs(c).getString(K_SERVER, "http://192.168.1.26:8080").trim();
        if (s.isEmpty()) s = "http://192.168.1.26:8080";
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }
    public static String key(Context c) {
        String k = prefs(c).getString(K_KEY, "SmsSync#2026#").trim();
        return k.isEmpty() ? "SmsSync#2026#" : k;
    }
    public static String ssid(Context c) {
        return prefs(c).getString(K_SSID, "perpower_5G");
    }
    public static void setServer(Context c, String v) {
        prefs(c).edit().putString(K_SERVER, v).apply();
    }
    public static void setKey(Context c, String v) {
        prefs(c).edit().putString(K_KEY, v).apply();
    }

    public static boolean smsGranted(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.READ_SMS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** 用默认服务器地址 + 共享密钥 + 默认 SSID */
    public static void applyDefaults(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (prefs(c).getString(K_SERVER, null) == null) e.putString(K_SERVER, "http://192.168.1.26:8080");
        if (prefs(c).getString(K_KEY, null) == null) e.putString(K_KEY, "SmsSync#2026#");
        if (prefs(c).getString(K_SSID, null) == null) e.putString(K_SSID, "perpower_5G");
        e.apply();
    }

    public static String buildJson(Context c, java.util.List<SmsItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"messages\":[");
        for (int i = 0; i < items.size(); i++) {
            SmsItem it = items.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(it.id)
              .append(",\"address\":\"").append(esc(it.address))
              .append("\",\"body\":\"").append(esc(it.body))
              .append("\",\"date\":").append(it.date)
              .append(",\"type\":").append(it.type).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
