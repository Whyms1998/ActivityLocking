package com.activitylock.app.watcher;

import android.app.IActivityController;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 以 shell 身份常驻的 Activity 启动拦截器（app_process 入口）。
 * 通过 IActivityManager.setActivityController 注册系统级控制器，
 * 被 APP 写入 blocked.json 的组件将在启动时被系统直接拒绝；
 * 规则若带有跳转深链（redirect），拒绝的同时会拉起该深链（如搜索页），
 * 实现"点桌面图标直达搜索页"。
 */
public class WatcherMain {

    static final String RULES_PATH =
            "/sdcard/Android/data/com.activitylock.app/files/blocked.json";
    static final String LOG_PATH = "/data/local/tmp/al_watcher.log";
    /** APP 用该文件判断常驻进程是否为当前版本（不匹配则杀掉重启）。 */
    static final String VERSION_MARKER = "/data/local/tmp/al_watcher.ver";
    static final int WATCHER_VERSION = 12;

    /** 组件全名 -> 跳转深链（空串表示仅拦截、不跳转）。 */
    static final ConcurrentHashMap<String, String> BLOCKED = new ConcurrentHashMap<>();
    /** 包名 -> 超额提示页组件（额度用完的 App 整包拒绝所有启动）。 */
    static final ConcurrentHashMap<String, String> PKG_BLOCKED = new ConcurrentHashMap<>();
    /** 额度提示页（本 App），超额包的拦截跳转目标。 */
    static final String QUOTA_GATE = "com.activitylock.app/com.activitylock.app.QuotaGateActivity";
    /** 包名 -> 上次跳转时刻，冷却期内不重复跳转。 */
    static final ConcurrentHashMap<String, Long> LAST_REDIRECT = new ConcurrentHashMap<>();
    static final long REDIRECT_DEDUPE_MS = 600;
    /**
     * 坏目标判定：跳转后短时间内、由应用内部（非桌面）再次启动被锁界面，
     * 说明这条深链的路由链绕回了被锁容器 → 该包 60 秒内不再跳转、只纯拦截。
     * 桌面点击（MAIN+LAUNCHER）永远是用户主动行为，立即跳转、不受冷却与抑制影响。
     * 规则文件变化时自动解除抑制。
     */
    static final long BAD_TARGET_WINDOW_MS = 5000;
    static final long BAD_TARGET_SUPPRESS_MS = 60000;
    static final ConcurrentHashMap<String, Long> SUPPRESS_UNTIL = new ConcurrentHashMap<>();
    static final ExecutorService REDIRECT_EXEC = Executors.newSingleThreadExecutor();

    /** 杀掉其他实例（App 升级/并发启动可能残留旧版进程；旧版会重新注册控制器抢占）。 */
    static void killOtherWatchers() {
        try {
            File proc = new File("/proc");
            String[] list = proc.list();
            if (list == null) return;
            String myPid = String.valueOf(android.os.Process.myPid());
            for (String pid : list) {
                if (!pid.matches("\\d+") || pid.equals(myPid)) continue;
                try {
                    String cmd = readCmdline("/proc/" + pid + "/cmdline");
                    if (cmd.contains("com.activitylock.app.watcher.WatcherMain")) {
                        android.os.Process.killProcess(Integer.parseInt(pid));
                        log("killed other watcher pid=" + pid);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readCmdline(String path) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(path);
        try {
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            String s = n <= 0 ? "" : new String(buf, 0, n);
            return s.replace('\0', ' ');
        } finally {
            in.close();
        }
    }

    static void log(String msg) {
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.FileWriter(LOG_PATH, true));
            w.println(android.os.SystemClock.currentThreadTimeMillis() + " " + msg);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    static void writeVersionMarker() {
        try {
            java.io.FileWriter w = new java.io.FileWriter(VERSION_MARKER);
            w.write(String.valueOf(WATCHER_VERSION));
            w.close();
        } catch (Throwable t) {
            log("marker error: " + t);
        }
    }

    static final String STATS_PATH = "/sdcard/Android/data/com.activitylock.app/files/blocked_stats.json";
    static final ConcurrentHashMap<String, Long> STATS_COUNT = new ConcurrentHashMap<>();
    static final java.util.ArrayDeque<String> STATS_RECENT = new java.util.ArrayDeque<>();
    static volatile long STATS_TOTAL = 0;
    static volatile String STATS_DATE = "";
    static volatile boolean STATS_DIRTY = false;

    static void loadStats() {
        try {
            File f = new File(STATS_PATH);
            String today = today();
            if (!f.exists()) { STATS_DATE = today; return; }
            org.json.JSONObject o = new org.json.JSONObject(readFile(STATS_PATH));
            STATS_TOTAL = o.optLong("total", 0);
            STATS_DATE = o.optString("date", today);
            org.json.JSONObject apps = o.optJSONObject("apps");
            if (apps != null) {
                java.util.Iterator<String> it = apps.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    STATS_COUNT.put(k, apps.optLong(k, 0));
                }
            }
            org.json.JSONArray rec = o.optJSONArray("recent");
            if (rec != null) {
                for (int i = 0; i < rec.length(); i++) STATS_RECENT.addLast(rec.optString(i));
            }
            if (!today.equals(STATS_DATE)) {
                STATS_COUNT.clear();
                STATS_DATE = today;
                STATS_DIRTY = true;
            }
        } catch (Throwable t) {
            log("stats load: " + t);
        }
    }

    static void countBlock(String pkg, String comp) {
        STATS_COUNT.merge(pkg, 1L, Long::sum);
        STATS_TOTAL++;
        synchronized (STATS_RECENT) {
            STATS_RECENT.addFirst(System.currentTimeMillis() + "|" + pkg + "|" + comp);
            while (STATS_RECENT.size() > 30) STATS_RECENT.removeLast();
        }
        STATS_DIRTY = true;
    }

    static void flushStats() {
        if (!STATS_DIRTY) return;
        try {
            String today = today();
            if (!today.equals(STATS_DATE)) {
                STATS_COUNT.clear();
                STATS_DATE = today;
            }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("date", STATS_DATE);
            o.put("total", STATS_TOTAL);
            org.json.JSONObject apps = new org.json.JSONObject();
            for (java.util.Map.Entry<String, Long> e : STATS_COUNT.entrySet()) {
                apps.put(e.getKey(), e.getValue());
            }
            o.put("apps", apps);
            org.json.JSONArray rec = new org.json.JSONArray();
            synchronized (STATS_RECENT) {
                for (String s : STATS_RECENT) rec.put(s);
            }
            o.put("recent", rec);
            java.io.FileWriter w = new java.io.FileWriter(STATS_PATH);
            w.write(o.toString());
            w.close();
            STATS_DIRTY = false;
        } catch (Throwable t) {
            log("stats flush: " + t);
        }
    }

    static String readFile(String path) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(path));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    static String today() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd");
        return f.format(new java.util.Date());
    }

    public static void main(String[] args) throws Exception {
        log("main start v" + WATCHER_VERSION + " uid=" + android.os.Process.myUid());
        killOtherWatchers();
        reloadRules();
        loadStats();
        log("rules loaded=" + BLOCKED + " pkg=" + PKG_BLOCKED);
        writeVersionMarker();
        IBinder am = getService("activity");
        if (am == null) {
            log("FATAL: activity service null");
            System.exit(1);
            return;
        }
        IActivityController controller = new Controller();        try {
            // 通过设备框架自带的 Stub 代理反射调用，事务码由设备运行时决定，避免版本偏移
            registerController(am, controller);
            log("controller registered");
        } catch (Throwable t) {
            log("FATAL register: " + t);
            System.exit(2);
            return;
        }

        // 每 2 秒重载规则；每 10 秒重新注册一次控制器（系统重启/AMS 重启后自恢复；
        // 注册是全局唯一的，缩短周期以在与其他同类工具竞争时更快夺回）
        Thread updater = new Thread(() -> {
            int tick = 0;
            while (true) {
                try {
                    Thread.sleep(2000);
                    int before = BLOCKED.size();
                    reloadRules();
                    if (BLOCKED.size() != before) log("rules changed=" + BLOCKED);
                    if (++tick % 5 == 0 && am.pingBinder()) {
                        killOtherWatchers();
                        registerController(am, controller);
                        log("controller re-registered tick=" + tick);
                    }
                    if (tick % 10 == 0) flushStats();
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
            ConcurrentHashMap<String, String> next = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, String> nextPkgs = new ConcurrentHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                Object el = arr.get(i);
                if (el instanceof org.json.JSONObject) {
                    org.json.JSONObject o = (org.json.JSONObject) el;
                    String pkg = o.optString("p", "");
                    if (!pkg.isEmpty()) {
                        nextPkgs.put(pkg, o.optString("r", "")); // 包级拦截（超额）
                        continue;
                    }
                    String comp = o.optString("c", "");
                    if (!comp.isEmpty()) next.put(comp, o.optString("r", ""));
                } else if (el instanceof String) {
                    next.put((String) el, ""); // 旧版纯字符串格式：仅拦截
                }
            }
            BLOCKED.clear();
            BLOCKED.putAll(next);
            PKG_BLOCKED.clear();
            PKG_BLOCKED.putAll(nextPkgs);
            // 规则变化（可能换了跳转目标）→ 解除坏目标抑制，给新目标机会
            SUPPRESS_UNTIL.clear();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 拒绝启动的同时拉起跳转目标（异步执行避免阻塞回调线程）。
     * 目标支持三种写法：深链（xhsdiscover://search）、组件名（pkg/Class）、
     * 裸类名（Class，视为被锁应用内的界面）。
     */
    /** 是否来自桌面点击（用户主动）：MAIN action + LAUNCHER 类别。 */
    static boolean isUserLaunch(Intent intent) {
        return "android.intent.action.MAIN".equals(intent.getAction())
                && intent.hasCategory("android.intent.category.LAUNCHER");
    }

    static void maybeRedirect(String pkg, Intent intent, String target) {
        // 深链被应用路由回被锁界面时携带同一深链 data，避免自环
        if (intent.getData() != null && target.equals(intent.getData().toString())) {
            log("REDIRECT skip(self) pkg=" + pkg);
            return;
        }
        log("REDIRECT " + target);
        REDIRECT_EXEC.execute(() -> {
            // 直连 binder 启动（更快、无子进程开销），失败回退 am start
            if (!startViaBinder(pkg, target)) {
                startViaAm(pkg, target);
            }
        });
    }

    static Intent buildTargetIntent(String pkg, String target) {
        Intent i = new Intent();
        if (target.contains("://")) {
            i.setAction("android.intent.action.VIEW");
            i.setData(android.net.Uri.parse(target));
        } else if (target.contains("/")) {
            i.setComponent(ComponentName.unflattenFromString(target));
        } else {
            i.setComponent(new ComponentName(pkg, target));
        }
        i.addFlags(0x10000000); // NEW_TASK
        return i;
    }

    static Object binderProxy(String service) {
        try {
            IBinder b = getService(service);
            if (b == null) return null;
            Class<?> stub = Class.forName("android.app.IActivityTaskManager$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        } catch (Throwable notTaskManager) {
            try {
                IBinder b = getService(service);
                if (b == null) return null;
                Class<?> stub = Class.forName("android.app.IActivityManager$Stub");
                return stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * 直连 binder 调 startActivityAsUser：反射在设备框架自己的 Stub 代理上
     * 自适配探测签名（含 Intent 参数、末参为 int userId），无硬编码事务码。
     * 兼容 11 参（Android ≤14）与 12 参（Android 15+，多 AttributionSource）。
     * 探测失败或调用异常返回 false，由 am start 兜底。
     */
    static boolean startViaBinder(String pkg, String target) {
        try {
            Intent i = buildTargetIntent(pkg, target);
            Object atm = binderProxy("activity_task");
            if (atm == null) atm = binderProxy("activity");
            if (atm == null) return false;
            for (java.lang.reflect.Method m : atm.getClass().getMethods()) {
                String n = m.getName();
                if (!n.equals("startActivityAsUser") && !n.equals("startActivity")) continue;
                Class<?>[] pt = m.getParameterTypes();
                int intentIdx = -1;
                for (int k = 0; k < pt.length; k++) {
                    if (pt[k] == Intent.class) { intentIdx = k; break; }
                }
                if (intentIdx < 0 || pt[pt.length - 1] != int.class) continue;
                Object[] args = new Object[pt.length];
                for (int k = 0; k < pt.length; k++) {
                    Class<?> t = pt[k];
                    if (t == Intent.class) args[k] = (k == intentIdx) ? i : null;
                    else if (t == String.class) args[k] = (k == 1) ? "com.android.shell" : null;
                    else if (t == int.class) {
                        args[k] = (k == pt.length - 1) ? 0 : (k == pt.length - 4) ? 0x10000000 : 0;
                    } else if (t.getName().endsWith("AttributionSource")) {
                        args[k] = attributionSource("com.android.shell");
                    } else args[k] = null;
                }
                try {
                    Object r = m.invoke(atm, args);
                    log("REDIRECT(binder " + n + "/" + pt.length + ") result=" + r);
                    return true;
                } catch (Throwable t2) {
                    log("REDIRECT binder try " + n + "/" + pt.length + " fail: " + t2.getCause());
                }
            }
            log("REDIRECT binder: no matching method");
        } catch (Throwable t) {
            log("REDIRECT binder fail: " + t);
        }
        return false;
    }

    /** 构造 shell 身份的 AttributionSource（Android 15+ startActivityAsUser 需要）。 */
    static Object attributionSource(String pkg) {
        try {
            Class<?> builderCls = Class.forName("android.content.AttributionSource$Builder");
            Object b = builderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
            b = builderCls.getMethod("setPackageName", String.class).invoke(b, pkg);
            return builderCls.getMethod("build").invoke(b);
        } catch (Throwable t) {
            return null;
        }
    }

    static void startViaAm(String pkg, String target) {
        try {
            String[] cmd;
            if (target.contains("://")) {
                cmd = new String[]{"/system/bin/am", "start", "--user", "0",
                        "-a", "android.intent.action.VIEW", "-d", target};
            } else if (target.contains("/")) {
                cmd = new String[]{"/system/bin/am", "start", "--user", "0", "-n", target};
            } else {
                cmd = new String[]{"/system/bin/am", "start", "--user", "0",
                        "-n", pkg + "/" + target};
            }
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            BufferedReader br = new BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String l;
            while ((l = br.readLine()) != null && out.length() < 400) {
                out.append(l).append('\n');
            }
            br.close();
            p.waitFor();
            log("REDIRECT exec code=" + p.exitValue() + " out=" + out.toString().trim());
        } catch (Throwable t) {
            log("REDIRECT exec error: " + t);
        }
    }

    static class Controller extends IActivityController.Stub {
        @Override
        public boolean activityStarting(Intent intent, String pkg) {
            if (intent == null) return true;
            ComponentName cn = intent.getComponent();
            if (cn == null) return true;
            if (BLOCKED.isEmpty() && PKG_BLOCKED.isEmpty()) return true;
            String flat = cn.flattenToString();
            String full = pkg + "/" + cn.getClassName();
            long now = android.os.SystemClock.elapsedRealtime();
            boolean userLaunch = isUserLaunch(intent);

            // 包级超额拦截：额度用完的 App，任何界面启动都拒绝并拉起额度提示页
            String gate = PKG_BLOCKED.get(pkg);
            if (gate != null) {
                log("BLOCK(pkg) " + pkg + (userLaunch ? " (launcher)" : ""));
                countBlock(pkg, full);
                Long lastG = LAST_REDIRECT.get(pkg);
                boolean dedupe = lastG != null && now - lastG < REDIRECT_DEDUPE_MS;
                if (!gate.isEmpty() && !dedupe) {
                    LAST_REDIRECT.put(pkg, now);
                    maybeRedirect(pkg, intent, gate);
                }
                return false;
            }

            String hit = BLOCKED.containsKey(flat) ? flat
                    : (BLOCKED.containsKey(full) ? full : null);
            if (hit == null) return true;
            String redirect = BLOCKED.get(hit);
            log("BLOCK " + hit + (userLaunch ? " (launcher)" : ""));
            countBlock(pkg, hit);

            // 坏目标抑制：跳转后由应用内部（非桌面）绕回被锁界面 → 深链无效，
            // 60 秒内只拦截不跳转（改跳转目标会重写规则自动解除）
            Long suppress = SUPPRESS_UNTIL.get(pkg);
            if (suppress != null && now < suppress) {
                log("BLOCK (redirect suppressed) " + hit);
                return false;
            }
            if (redirect == null || redirect.isEmpty()) return false;

            boolean isGate = QUOTA_GATE.equals(redirect);
            Long last = LAST_REDIRECT.get(pkg);
            // 去重：同一次点击触发的重复启动（~0.6s 内）
            if (last != null && now - last < REDIRECT_DEDUPE_MS) {
                log("BLOCK (dedupe) " + hit);
                return false;
            }
            if (!userLaunch && !isGate && last != null && now - last < BAD_TARGET_WINDOW_MS) {
                // 非桌面来源的链路在跳转后立即绕回被锁界面 → 坏目标
                SUPPRESS_UNTIL.put(pkg, now + BAD_TARGET_SUPPRESS_MS);
                log("BAD-TARGET suppress 60s pkg=" + pkg);
                return false;
            }
            // 桌面点击（用户主动）：无条件立即跳转——无冷却、无抑制，点多少次都有响应
            LAST_REDIRECT.put(pkg, now);
            maybeRedirect(pkg, intent, redirect);
            return false;
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
