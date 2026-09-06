package com.activitylock.app.watcher;

import android.app.IActivityController;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.Set;

/**
 * 以 shell 身份常驻的 Activity 启动拦截器（app_process 入口）。
 * 通过 IActivityManager.setActivityController 注册系统级控制器，
 * 被 APP 写入 blocked.json 的组件将在启动时被系统直接拒绝。
 */
public class WatcherMain {

    static final String RULES_PATH =
            "/sdcard/Android/data/com.activitylock.app/files/blocked.json";
    static final String LOG_PATH = "/data/local/tmp/al_watcher.log";

    static final Set<String> BLOCKED = ConcurrentHashMap.newKeySet();

    static void log(String msg) {
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.FileWriter(LOG_PATH, true));
            w.println(android.os.SystemClock.currentThreadTimeMillis() + " " + msg);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        log("main start uid=" + android.os.Process.myUid());
        reloadRules();
        log("rules loaded=" + BLOCKED);
        IBinder am = getService("activity");
        if (am == null) {
            log("FATAL: activity service null");
            System.exit(1);
            return;
        }
        IActivityController controller = new Controller();
        try {
            // 通过设备框架自带的 Stub 代理反射调用，事务码由设备运行时决定，避免版本偏移
            registerController(am, controller);
            log("controller registered");
        } catch (Throwable t) {
            log("FATAL register: " + t);
            System.exit(2);
            return;
        }

        // 每 2 秒重载规则；每 30 秒重新注册一次控制器（系统重启/AMS 重启后自恢复）
        Thread updater = new Thread(() -> {
            int tick = 0;
            while (true) {
                try {
                    Thread.sleep(2000);
                    int before = BLOCKED.size();
                    reloadRules();
                    if (BLOCKED.size() != before) log("rules changed=" + BLOCKED);
                    if (++tick % 15 == 0 && am.pingBinder()) {
                        registerController(am, controller);
                        log("controller re-registered tick=" + tick);
                    }
                } catch (Throwable t) {
                    log("updater error: " + t);
                }
            }
        });
        updater.setDaemon(true);
        updater.start();

        Looper.prepare();
        new CountDownLatch(1).await(); // 常驻
        Looper.loop();
    }

    /** 用设备框架自己的 IActivityManager.Stub 代理注册控制器（shell 身份，免事务码硬编码）。 */
    static void registerController(IBinder rawAmBinder, IActivityController controller)
            throws Exception {
        Class<?> stub = Class.forName("android.app.IActivityManager$Stub");
        Object am = stub.getMethod("asInterface", IBinder.class).invoke(null, rawAmBinder);
        Class<?> iAm = Class.forName("android.app.IActivityManager");
        iAm.getMethod("setActivityController",
                IActivityController.class, boolean.class).invoke(am, controller, false);
    }

    static IBinder getService(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    static void reloadRules() {
        try {
            File f = new File(RULES_PATH);
            if (!f.exists()) return;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
            Set<String> next = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < arr.length(); i++) {
                next.add(arr.getString(i));
            }
            BLOCKED.clear();
            BLOCKED.addAll(next);
        } catch (Throwable ignored) {
        }
    }

    static class Controller extends IActivityController.Stub {
        @Override
        public boolean activityStarting(Intent intent, String pkg) {
            if (intent == null) return true;
            ComponentName cn = intent.getComponent();
            if (cn == null) return true;
            if (BLOCKED.isEmpty()) return true;
            String flat = cn.flattenToString();
            if (BLOCKED.contains(flat)) {
                log("BLOCK " + flat);
                return false;
            }
            String full = pkg + "/" + cn.getClassName();
            boolean allow = !BLOCKED.contains(full);
            log((allow ? "ALLOW " : "BLOCK ") + full);
            return allow;
        }

        @Override
        public boolean activityResuming(String pkg) {
            return true;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg,
                                  String longMsg, long timeMillis, String stackTrace) {
            return true;
        }

        @Override
        public int appEarlyNotResponding(String processName, int pid, String annotation) {
            return 0;
        }

        @Override
        public int appNotResponding(String processName, int pid, String processStats) {
            return 0;
        }

        @Override
        public int systemNotResponding(String msg) {
            return 0;
        }
    }
}
