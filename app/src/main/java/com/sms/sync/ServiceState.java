package com.sms.sync;

import android.app.ActivityManager;
import android.content.Context;

/** 判断 SyncService 是否在运行 */
public final class ServiceState {
    private ServiceState() {}
    public static boolean isRunning(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(200)) {
            if (SyncService.class.getName().equals(s.service.getClassName())) return true;
        }
        return false;
    }
}
