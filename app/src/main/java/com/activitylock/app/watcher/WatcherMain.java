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
    static final int WATCHER_VERSION = com.activitylock.app.core.WatcherVersion.VERSION;

    /** 组件全名 -> 跳转深链（空串表示仅拦截、不跳转）。 */
    static final ConcurrentHashMap<String, String> BLOCKED = new ConcurrentHashMap<>();
    /** 包名 -> 超额提示页组件（额度用完的 App 整包拒绝所有启动）。 */
    static final ConcurrentHashMap<String, String> PKG_BLOCKED = new ConcurrentHashMap<>();
    /** 组件锁有效期（epoch 毫秒）：到期自动放行（到期解锁）。0/缺失=不自动到期。 */
    static final ConcurrentHashMap<String, Long> BLOCKED_UNTIL = new ConcurrentHashMap<>();

    // ---- 到点跳转：连续使用 N 分钟自动打开学习 App ----
    static final ConcurrentHashMap<String, long[]> SESSION_LIMIT = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> SESSION_TARGET = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Long> SESSION_ACC = new ConcurrentHashMap<>();
    static volatile String CUR_PKG = null;
    static volatile long LAST_EVT = 0;
    static final ConcurrentHashMap<String, Long> SESSION_LASTJUMP = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Long> SESSION_LEAVE = new ConcurrentHashMap<>();     // 各包最近离开时刻
    static final ConcurrentHashMap<String, long[]> SESSION_JUMPS = new ConcurrentHashMap<>();    // {当日次数, 天纪元}
    static final ConcurrentHashMap<String, long[]> SESSION_EXHAUSTED = new ConcurrentHashMap<>(); // {1, 天纪元} 当日已收走
    static final ConcurrentHashMap<String, Integer> SESSION_MAXJ = new ConcurrentHashMap<>();     // 当日第 N 次后锁定，0=不启用
    static final ConcurrentHashMap<String, Integer> SESSION_GRACE = new ConcurrentHashMap<>();    // 离开宽限期（秒），可配
    static final ConcurrentHashMap<String, String> SESSION_MODE = new ConcurrentHashMap<>();      // jump=跳转 / lock=直接锁定
    static final long SESSION_GAP_MS = 10_000L;      // 前台事件间隔超过此值判定为"离开过"（tick 每 2s，精度足够）
    static final long JUMP_COOLDOWN_MS = 5 * 60_000L; // 跳转后冷静期：切回立即再送

    /**
     * ★ 自我跳转豁免窗口（v1.28.0）。
     *
     * 背景：activityStarting 只拦「显式组件」（cn == null 的隐式深链在第 722 行直接放行），
     * 所以 ① 「scheme://」这类隐式跳转永远不会命中自己的规则，而
     *     ② 「组件|scheme://」（显式组件 + data）跳转的目标组件**往往就是被拦的那个**。
     * 真机 2026-10-26：酷安 coolmarket:// 同时注册在 MainActivity 与 AppLinkActivity
     * （后者无 authority 限制）→ 隐式写法必弹系统选择器，只能用显式 MainActivity + data；
     * 而 MainActivity 正是规则里被拦的组件 → 不豁免就会自己拦自己（或被判 BAD-TARGET 抑制 60s）。
     *
     * 判据：跳转发出后 [SELF_JUMP_WINDOW_MS] 内，该包**非桌面来源**的启动一律放行。
     * 用户主动点桌面图标（MAIN + LAUNCHER）不受影响，仍然拦截。
     */
    static final long SELF_JUMP_WINDOW_MS = 3_000L;
    static final java.util.concurrent.ConcurrentHashMap<String, Long> SELF_JUMP_UNTIL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 离开宽限期（秒）：切出其他 App 后在此时间内回来会接着算，超时重新计时。 */
    static long graceSec(String pkg) {
        Integer g = SESSION_GRACE.get(pkg);
        return g == null ? 120L : g.longValue();
    }
    /** 全局豁免截止时间（epoch ms）：临时解锁期间放行所有启动，0=无。 */
    static volatile long GLOBAL_EXEMPT_UNTIL = 0;
    /** 按天拦截计数（ISO 日期 -> 次数），供周报趋势。 */
    static final ConcurrentHashMap<String, Long> STATS_DAILY = new ConcurrentHashMap<>();
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

    /** 日志体积上限（512KB）。超过则只保留尾部一半，防止 /data/local/tmp 无限增长。 */
    static final long MAX_LOG_BYTES = 512 * 1024L;
    /** 写入计数：每 64 次检查一次体积，避免每次写都 stat 文件。 */
    static volatile int LOG_WRITES = 0;

    static void log(String msg) {
        try {
            java.io.File lf = new java.io.File(LOG_PATH);
            // ★ v22：体积安全网——reload 短路后正常路径几乎不写文件，
            //   这里兜住异常/调试路径可能产生的突发写入，保证文件不会无限增长。
            if ((++LOG_WRITES & 63) == 0 && lf.exists() && lf.length() > MAX_LOG_BYTES) {
                rotateLog(lf);
            }
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.FileWriter(LOG_PATH, true));
            w.println(android.os.SystemClock.currentThreadTimeMillis() + " " + msg);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 保留日志尾部一半内容并前移，使文件体积回落到上限以下。 */
    static void rotateLog(java.io.File lf) {
        try {
            long keep = MAX_LOG_BYTES / 2;
            long len = lf.length();
            if (len <= keep) return;
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lf, "rw");
            try {
                raf.seek(len - keep);
                byte[] buf = new byte[(int) keep];
                int n = raf.read(buf);
                if (n > 0) {
                    raf.seek(0);
                    raf.write(buf, 0, n);
                    raf.setLength(n);
                }
            } finally {
                raf.close();
            }
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
            org.json.JSONObject daily = o.optJSONObject("daily");
            if (daily != null) {
                java.util.Iterator<String> it = daily.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    STATS_DAILY.put(k, daily.optLong(k, 0));
                }
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
        STATS_DAILY.merge(today(), 1L, Long::sum);
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
            org.json.JSONObject daily = new org.json.JSONObject();
            for (java.util.Map.Entry<String, Long> e : STATS_DAILY.entrySet()) {
                daily.put(e.getKey(), e.getValue());
            }
            o.put("daily", daily);
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
                        // ★ v22：周期重注册改为静默执行，仅每 5 分钟打一次心跳
                        //   （旧实现每 10 秒一条 "controller re-registered"，
                        //    一天 8640 行纯噪音）
                        if (tick % 150 == 0) log("watcher alive tick=" + tick);
                    }
                    if (tick % 10 == 0) flushStats();
                    sessionTick();
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

    /**
     * 上一次成功解析的规则原文。
     *
     * ★ v22 新增：内容没变就直接返回。
     *   旧实现每 2 秒被 updater 调用一次，**无条件**重新解析并打印两行日志
     *   （其中 `reload file:` 还带 200 字符的 JSON）——一天约 8.6 万行 / 约 10MB，
     *   `/data/local/tmp/al_watcher.log` 只会无限增长。
     *   规则只在用户改动设置时才变，所以这一句能把日志量砍掉 99% 以上，
     *   同时省掉每 2 秒一次的 JSON 解析。
     *   另配套 log() 的体积轮转（见 MAX_LOG_BYTES），双保险。
     */
    static volatile String LAST_RULES_RAW = null;

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
            String raw = sb.toString();
            // ★ 内容未变 → 直接返回，不解析、不写日志
            if (raw.equals(LAST_RULES_RAW)) return;
            LAST_RULES_RAW = raw;
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            log("reload file: " + raw.substring(0, Math.min(raw.length(), 200)));
            ConcurrentHashMap<String, String> next = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, String> nextPkgs = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Long> nextUntil = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, long[]> nextSess = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, String> nextSessT = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Integer> nextSessN = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Integer> nextSessG = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, String> nextSessM = new ConcurrentHashMap<>();
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
                    if (!comp.isEmpty()) {
                        next.put(comp, o.optString("r", ""));
                        long until = o.optLong("u", 0);
                        if (until > 0) nextUntil.put(comp, until);
                    }
                    // 到点跳转：{"f":触发包,"s":时限秒,"t":目标}
                    String sp = o.optString("f", "");
                    if (!sp.isEmpty()) {
                        nextSess.put(sp, new long[]{o.optLong("s", 0) * 1000L});
                        nextSessT.put(sp, o.optString("t", ""));
                        nextSessN.put(sp, o.optInt("n", 3));
                        nextSessM.put(sp, o.optString("m", "jump"));
                        nextSessG.put(sp, o.optInt("g", 120));
                    }
                    // 全局临时豁免：{"g":1,"u":到期毫秒}，过期即视为无豁免
                    if (o.optInt("g", 0) == 1) {
                        long u = o.optLong("u", 0);
                        GLOBAL_EXEMPT_UNTIL = u > System.currentTimeMillis() ? u : 0;
                    }
                } else if (el instanceof String) {
                    next.put((String) el, ""); // 旧版纯字符串格式：仅拦截
                }
            }
            BLOCKED.clear();
            BLOCKED.putAll(next);
            PKG_BLOCKED.clear();
            PKG_BLOCKED.putAll(nextPkgs);
            BLOCKED_UNTIL.clear();
            BLOCKED_UNTIL.putAll(nextUntil);
            SESSION_LIMIT.clear();
            SESSION_LIMIT.putAll(nextSess);
            SESSION_TARGET.clear();
            SESSION_TARGET.putAll(nextSessT);
            SESSION_MAXJ.clear();
            SESSION_MAXJ.putAll(nextSessN);
            SESSION_GRACE.clear();
            SESSION_GRACE.putAll(nextSessG);
            SESSION_MODE.clear();
            SESSION_MODE.putAll(nextSessM);
            // 规则被删除/关闭的包，清掉它的当日计数与收走状态（app 里关开关即可解除锁定）
            for (String k : SESSION_JUMPS.keySet()) {
                if (!nextSess.containsKey(k)) SESSION_JUMPS.remove(k);
            }
            for (String k : SESSION_EXHAUSTED.keySet()) {
                if (!nextSess.containsKey(k)) SESSION_EXHAUSTED.remove(k);
            }
            log("reload: c=" + next.size() + " p=" + nextPkgs.size() + " f=" + nextSess.size()
                + " g=" + (GLOBAL_EXEMPT_UNTIL > 0 ? 1 : 0));
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
    /**
     * 前台事件：累计连续使用时长。
     * 切走会清零，但留有 2 分钟宽限期——快速回个微信再回来会接着算，
     * 超过 2 分钟才视为真正离开、重新计时（防"秒切重置"规避）。
     */
    static void sessionEvent(String pkg) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            if (pkg.equals(CUR_PKG)) {
                long delta = now - LAST_EVT;
                Long acc = SESSION_ACC.get(pkg);
                long base = acc == null ? 0 : acc;
                if (delta >= 0 && delta < SESSION_GAP_MS) {
                    SESSION_ACC.put(pkg, base + delta); // 连续在场
                } else if (delta <= graceSec(pkg) * 1000L) {
                    // 离开但在宽限期内（息屏/回消息）：保留累计，不把离开时间计入
                } else {
                    SESSION_ACC.put(pkg, 0L); // 离开超过宽限期，重新计
                }
            } else {
                String curPkg = CUR_PKG;
                if (curPkg != null) SESSION_LEAVE.put(curPkg, now);
                Long leave = SESSION_LEAVE.get(pkg);
                Long acc = SESSION_ACC.get(pkg);
                boolean withinGrace = leave != null && (now - leave) <= graceSec(pkg) * 1000L;
                if (!withinGrace || acc == null) {
                    SESSION_ACC.put(pkg, 0L); // 刚进入，或离开超过宽限期：从零开始
                } // 宽限期内切回：保留累计，接着算
                CUR_PKG = pkg;
            }
            LAST_EVT = now;
        } catch (Throwable ignored) {
        }
    }

    /** 周期兜底：前台停留期间没有新事件时由 2s tick 累计并检查阈值。 */
    static void sessionTick() {
        try {
            String cur = CUR_PKG;
            if (cur == null || SESSION_LIMIT.isEmpty()) return;
            long now = android.os.SystemClock.elapsedRealtime();
            long delta = now - LAST_EVT;
            if (delta < 0 || delta >= SESSION_GAP_MS) return; // 间隔过久：可能已离开/息屏
            Long acc = SESSION_ACC.get(cur);
            SESSION_ACC.put(cur, (acc == null ? 0 : acc) + delta);
            LAST_EVT = now;
            sessionCheck(cur);
        } catch (Throwable ignored) {
        }
    }

    static void sessionCheck(String pkg) {
        try {
            long[] lim = SESSION_LIMIT.get(pkg);
            if (lim == null) return;
            Long acc = SESSION_ACC.get(pkg);
            if (acc == null || acc < lim[0]) return;
            long now = android.os.SystemClock.elapsedRealtime();
            Long lj = SESSION_LASTJUMP.get(pkg);
            if (lj != null && now - lj < JUMP_COOLDOWN_MS) return;
            SESSION_ACC.put(pkg, 0L);
            SESSION_LASTJUMP.put(pkg, now);
            // 直接锁定模式：连续刷满即当日收走，不跳转（必须在目标检查之前，lock 无 target）
            String mode = SESSION_MODE.get(pkg);
            if ("lock".equals(mode)) {
                long todayL = System.currentTimeMillis() / 86_400_000L;
                SESSION_EXHAUSTED.put(pkg, new long[]{1, todayL});
                log("SESSION-LOCK " + pkg + " acc=" + (acc / 1000) + "s -> locked for today");
                return;
            }
            String target = SESSION_TARGET.get(pkg);
            if (target == null || target.isEmpty()) return;
            // 当日跳转次数 + 升级判定：第 N 次后当日收走（次日自动恢复）
            long today = System.currentTimeMillis() / 86_400_000L;
            long[] jc = SESSION_JUMPS.get(pkg);
            long count = (jc != null && jc[1] == today) ? jc[0] : 0;
            count++;
            SESSION_JUMPS.put(pkg, new long[]{count, today});
            Integer mj = SESSION_MAXJ.get(pkg);
            if (mj != null && mj > 0 && count >= mj) {
                SESSION_EXHAUSTED.put(pkg, new long[]{1, today});
                log("SESSION-EXHAUSTED " + pkg + " jumps=" + count);
            }
            log("SESSION-JUMP " + pkg + " acc=" + (acc / 1000) + "s jumps=" + count + " -> " + target);
            launchTargetAsync(pkg, target);
        } catch (Throwable ignored) {
        }
    }

    static void launchTargetAsync(String pkg, String target) {
        REDIRECT_EXEC.execute(() -> {
            if (!startViaBinder(pkg, target)) {
                startViaAm(pkg, target);
            }
        });
    }

    /** 冷静期内切回 / 当日已收走：都算命中闸。 */
    static boolean sessionGateHit(String pkg) {
        long today = System.currentTimeMillis() / 86_400_000L;
        long[] exh = SESSION_EXHAUSTED.get(pkg);
        if (exh != null && exh[1] == today) return true;
        if (SESSION_LIMIT.containsKey(pkg)) {
            Long lj = SESSION_LASTJUMP.get(pkg);
            if (lj != null && android.os.SystemClock.elapsedRealtime() - lj < JUMP_COOLDOWN_MS) return true;
        }
        return false;
    }

    /** 命中闸：把学习 App 重新拉到前台。 */
    static void enforceSessionGate(String pkg) {
        try {
            String target = SESSION_TARGET.get(pkg);
            log("SESSION-GATE " + pkg + " -> " + (target == null ? "" : target));
            if (target != null && !target.isEmpty()) launchTargetAsync(pkg, target);
        } catch (Throwable ignored) {
        }
    }

    /** 是否来自桌面点击（用户主动）：MAIN action + LAUNCHER 类别。 */
    static boolean isUserLaunch(Intent intent) {
        return "android.intent.action.MAIN".equals(intent.getAction())
                && intent.hasCategory("android.intent.category.LAUNCHER");
    }

    static void maybeRedirect(String pkg, Intent intent, String target) {
        // 深链被应用路由回被锁界面时携带同一深链 data，避免自环。
        // 组合写法「组件|uri」只比 uri 部分（组件每次都会被 mainActivity 透传回来）。
        int bar = target.indexOf('|');
        String targetUri = bar > 0 ? target.substring(bar + 1) : target;
        if (intent.getData() != null && targetUri.equals(intent.getData().toString())) {
            log("REDIRECT skip(self) pkg=" + pkg);
            return;
        }
        log("REDIRECT " + target);
        // ★ 先开豁免窗口再发：显式组件跳转的目标组件往往就是被拦的那个，
        //   不豁免会被自己的规则二次拦下（或被判成 BAD-TARGET 抑制 60s）。
        SELF_JUMP_UNTIL.put(pkg, android.os.SystemClock.elapsedRealtime() + SELF_JUMP_WINDOW_MS);
        REDIRECT_EXEC.execute(() -> {
            // 直连 binder 启动（更快、无子进程开销），失败回退 am start
            if (!startViaBinder(pkg, target)) {
                startViaAm(pkg, target);
            }
        });
    }

    /**
     * 跳转目标三写法：
     *   ① {@code scheme://host/path} —— 隐式深链（ACTION_VIEW + data）
     *   ② {@code pkg/pkg.Cls} 或 {@code .Cls} —— 显式组件（无 data）
     *   ③ {@code pkg/pkg.Cls|scheme://host/path} —— ★ 显式组件 + data
     *
     * ★ ③ 不可省：同一 scheme 注册在多个组件上时，① 会被系统判为二义 → 弹
     *   ResolverActivity 让用户选（真机 2026-10-26 酷安实测：coolmarket:// 同时注册在
     *   MainActivity〔带 authority + path〕和 AppLinkActivity〔只有 scheme，无任何限制〕）。
     *   选择器是系统界面，不受拦截器控制，用户点一下就直接进 App 了 —— 等于放行。
     */
    static Intent buildTargetIntent(String pkg, String target) {
        Intent i = new Intent();
        int bar = target.indexOf('|');
        if (bar > 0) {
            i.setAction("android.intent.action.VIEW");
            i.setComponent(ComponentName.unflattenFromString(target.substring(0, bar)));
            i.setData(android.net.Uri.parse(target.substring(bar + 1)));
        } else if (target.contains("://")) {
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
            int bar = target.indexOf('|');
            if (bar > 0) {
                // 显式组件 + data（ProcessBuilder 数组传参不经 shell，'|' 无转义问题）
                cmd = new String[]{"/system/bin/am", "start", "--user", "0",
                        "-a", "android.intent.action.VIEW",
                        "-n", target.substring(0, bar), "-d", target.substring(bar + 1)};
            } else if (target.contains("://")) {
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
            // 会话计时（到点跳转）：临时解锁期间不累计（休息时间不催学习）
            if (GLOBAL_EXEMPT_UNTIL > 0 && System.currentTimeMillis() < GLOBAL_EXEMPT_UNTIL) {
                LAST_EVT = android.os.SystemClock.elapsedRealtime();
            } else {
                sessionEvent(pkg);
            }
            // 全局临时豁免：临时解锁期间放行一切（优先于规则/包级/超额拦截）
            if (GLOBAL_EXEMPT_UNTIL > 0 && System.currentTimeMillis() < GLOBAL_EXEMPT_UNTIL) {
                log("ALLOW(global-exempt) " + pkg + "/" + cn.getClassName());
                return true;
            }
            // 到点跳转闸：冷静期内切回 / 当日已收走 → 拦截并送学习 App
            if (SESSION_TARGET.containsKey(pkg) && sessionGateHit(pkg)) {
                enforceSessionGate(pkg);
                return false;
            }
            if (BLOCKED.isEmpty() && PKG_BLOCKED.isEmpty()) return true;
            String flat = cn.flattenToString();
            String full = pkg + "/" + cn.getClassName();
            long now = android.os.SystemClock.elapsedRealtime();
            boolean userLaunch = isUserLaunch(intent);

            // ★ 自我跳转豁免：刚由本拦截器发出的跳转，其目标组件往往就是被拦的那个
            //   （典型：酷安「组件|深链」写法，目标正是 MainActivity）。放行整条跳转链，
            //   否则显式组件跳转会被自己的规则二次拦下。用户点桌面图标不受影响（userLaunch）。
            Long selfUntil = SELF_JUMP_UNTIL.get(pkg);
            if (!userLaunch && selfUntil != null && now < selfUntil) {
                log("ALLOW(self-jump) " + flat);
                return true;
            }

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
            // 到期解锁：锁定有效期已过 → 自动解除该条锁定（放行并清掉内存规则）
            Long until = BLOCKED_UNTIL.get(hit);
            if (until != null && until > 0 && System.currentTimeMillis() >= until) {
                BLOCKED.remove(hit);
                BLOCKED_UNTIL.remove(hit);
                log("EXPIRED-UNLOCK " + hit);
                return true;
            }
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
            if (GLOBAL_EXEMPT_UNTIL > 0 && System.currentTimeMillis() < GLOBAL_EXEMPT_UNTIL) {
                LAST_EVT = android.os.SystemClock.elapsedRealtime();
            } else {
                sessionEvent(pkg);
                sessionCheck(pkg);
                if (SESSION_TARGET.containsKey(pkg) && sessionGateHit(pkg)) {
                    enforceSessionGate(pkg);
                }
            }
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
