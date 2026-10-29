package com.dshmobile.pethost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.ViewTreeObserver;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.inputmethod.InputMethodManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 桌宠悬浮窗 v2 —— Android 版的「dsh-pet 桌面宿主」。
 *
 * 渲染窗：一个 WebView 窗口，加载 dsh-pet 的桌面渲染端页面
 *   http://127.0.0.1:3080/dsh-pet-android/desktop/
 * （插件把上游 runtime/electron-helper 原样下发；动画/气泡/菜单/拖拽甩抛/余额/对话全在页面里）。
 * 窗口尺寸 = 宠物包围盒 + 余量，位置逐帧由页面 window.petBridge.setBounds() 上报后由本类搬运。
 * 整窗 FLAG_NOT_TOUCHABLE —— 余量环（给气泡/菜单留的透明区）不挡下层应用的点击，
 * 这就是「碰撞体积太大」的根治办法。
 *
 * 触摸窗：另一块只覆盖「宠物身体 ∪ 页面上报的可点控件」的小窗口，永远可触摸。
 * 收到 MotionEvent 后按屏幕物理像素转发进页面 window.__petHost，由它直接调 PetSprite 的
 * onPointerDown/Move/Up/onClick/onContextMenu —— 拖拽/甩抛/点击/长按菜单与桌面端同一套逻辑。
 *
 * 调试端口 127.0.0.1:17999：/dump 看内部状态，/eval?js=… 直接求值页面 JS。
 */
public class PetService extends Service {

    private static final String TAG = "PetHost";
    /** 打进 /report 里，用来确认手机装的是哪一版 */
    private static final String APP_VER = "6.8";
    /** 页面静默多久就 reload / 重建窗口（2.8 的同款阈值，实测能区分"发呆"和"渲染进程死了"） */
    private static final long SILENT_RELOAD_MS = 60000;
    private static final long SILENT_RECREATE_MS = 180000;
    private static final long WATCH_MS = 4000;
    /** 宿主侧存活探测的间隔（v6.7）：不依赖页面定时器，所以不怕节流 */
    private static final long PING_MS = 8000;
    private static volatile boolean running = false;

    private WindowManager wm;
    private Handler main;
    private WebView web;
    private View touch;
    private WindowManager.LayoutParams lpRender;
    private WindowManager.LayoutParams lpTouch;

    private float density = 3f;
    private int screenW = 1080, screenH = 2400;
    private int areaTop = 0, areaH = 2200;
    /** 工作区宽度（物理 px）。横屏时导航栏跑到侧边，可用宽度比 screenW 窄一点 */
    private int areaW = 1080;

    private final Object lock = new Object();
    private boolean pending = false;
    private int reqX = 0, reqY = 0, reqW = 600, reqH = 470;
    private double boxX = 150, boxY = 150, petSize = 300, marginT = 150, marginR = 150;

    private volatile String inputRegions = null;
    private volatile boolean interactiveFlag = false;
    /** 页面正在用输入（拖拽中 / 菜单开着 / 对话窗开着 / 可展开气泡）：此时整窗都要接住触摸 */
    private volatile boolean inputBusy = false;
    private volatile String touchRegionKey = "";

    private final ArrayDeque<String> logs = new ArrayDeque<String>();
    private volatile String lastJsInfo = "";
    private volatile String lastError = "";
    private volatile long pageLoads = 0;
    /** 页面最后一次"我还活着"的时间（宿主 ping / 页面心跳 / 桥调用）。看门狗只看它。 */
    private volatile long lastPageAliveAt = 0;
    /** 宿主 ping 最近一次成功的时间与次数（/dump 里能看到，区分"她安静"和"真死了"） */
    private volatile long pingOkAt = 0;
    private volatile int pingN = 0;
    /** 模式 A 自检代数：每进一次 A 加一，防止旧的定时器误判新的窗口 */
    private volatile int a11yGen = 0;
    /** 建窗代数：老窗口留下的延时任务（"触摸区没回调就自杀"）必须失效 */
    private volatile int winGen = 0;
    /** 补丁层这版是从哪来的："asset"（APK 拦截并发）/"asset-inject"（页面模板没引，宿主兜底注入）/"plugin"（老路径） */
    private volatile String shimFrom = "plugin";
    /** 菜单增量补丁 host-extra.js 这版从哪来：plugin = 插件伺服那份跑起来了；asset-inject = 插件没给，宿主推的 assets 那份 */
    private volatile String extraFrom = "plugin";

    // 实时调参（/dsh-pet-android/desktop/tune）：现场校准命中区与画面之间的偏差，不必重装
    // 页面亲自上报的身体命中区（窗口内物理像素）——比"整块舞台"准得多
    private volatile boolean bodyValid = false;
    private volatile float bodyRelX = 0, bodyRelY = 0, bodyRelW = 0, bodyRelH = 0;

    // 窗口合成参数。
    // ★ 下面这些默认值**就是出厂配置**（2026-10-28 真机验证通过的组合）：
    //   translucent + 硬件加速 + 完全不透明 + overlay 类型 + touchable 标志集。
    //   /sdcard/.dsh-phone/pet-tune.json 只是**调试期覆盖**：文件不存在、或字段缺失，
    //   一律落回这些值。所以装完 APK 不依赖任何外部文件就能正常显示。
    private volatile int winApplySeq = -1;
    private volatile String winFormat = "translucent";
    private volatile boolean winHw = true;
    private volatile double winViewAlpha = 1.0;
    private volatile double winWinAlpha = 1.0;
    private volatile boolean winProbe = false;
    private volatile String winType = "overlay";
    private volatile String winFlagSet = "touchable";
    /** 窗口模型：board = 窗口跟着她跑（模式 B，默认）；a11y = 全屏穿透显示窗 + 独立触摸窗（模式 A） */
    /** auto（默认）= 无障碍可用就用模式 A，否则模式 B；board / a11y 只是调试用的强制值。
     *  ★ 默认值为 auto 的意义：**APK 装下来就是最终形态，不需要任何外部配置文件**。 */
    private volatile String winMode = "board";
    private volatile boolean a11yOn = false;
    /** 本进程内不再尝试模式 A（建窗失败 / 自检失败），避免死循环 */
    private volatile boolean a11yBlocked = false;
    /** 已经替用户把「设置 → 无障碍」拉起来过一次（只提示一次，别每次调参都弹） */
    private volatile boolean a11yPrompted = false;
    private static volatile PetService INST;
    private ProveView probeView;
    private WindowManager.LayoutParams lpProbe;
    private volatile String winLayer = "none";
    /** 外部覆盖文件是否真的生效过（/dump 里可查；false = 纯内置默认） */
    private volatile boolean tuneFileSeen = false;

    private volatile int actX = 0, actY = 0;
    private volatile boolean actValid = false;

    private volatile float tuneOffX = 0, tuneOffY = 0, tuneExtraW = 0, tuneExtraH = 0, tunePad = 0;
    private volatile long tuneReloadSeq = -1;
    private boolean tuneSeen = false;

    private float downX = 0, downY = 0;
    private boolean touchDown = false, touchMoved = false, consumed = false;

    private static volatile PetService INSTANCE;

    /**
     * 输入框 Activity 把文字交回来：交给页面里的 __petHost.chatSend()。
     * 页面侧负责 POST 上游 /chat（与 dsh-pet 弹窗同一个接口、同一份 memory.json）。
     * 用带回调的 evaluateJavascript 把页面的返回值打日志——上一版"发了没反应"就是因为
     * 这条链路的返回值没人看，根本分不清是没调到、还是页面里失败了。
     */
    public static void deliverChat(final String text) {
        final PetService svc = INSTANCE;
        if (svc == null || text == null || text.isEmpty()) {
            logChat("deliver chat 丢弃：svc=" + (svc != null) + " len=" + (text == null ? -1 : text.length()));
            return;
        }
        svc.main.post(() -> {
            try {
                if (svc.web == null) {
                    svc.log("deliver chat: web=null");
                    return;
                }
                String code = "window.__petHost&&window.__petHost.chatSend(" + JSONObject.quote(text) + ")";
                svc.web.evaluateJavascript(code, v -> svc.log("deliver chat(" + text.length() + "字) -> " + v));
            } catch (Throwable t) {
                svc.log("deliver chat failed " + t);
            }
        });
    }

    /** Activity 里也能写进同一个诊断日志（/dump 的 logs 能看到）。 */
    public static void logChat(String s) {
        PetService svc = INSTANCE;
        if (svc != null) svc.log(s);
        else Log.i(TAG, s);
    }

    // ---------------- 对话：隐形输入代理（2026-10-29） ----------------
    // 界面在页面里（原版 dsh-pet 弹窗），键盘在 ChatInputActivity 里（2dp 透明窗口），
    // 中间用这三条回灌：草稿 / 输入法高度 / 关闭。

    /** 当前在开的隐形输入代理（Activity）；null = 键盘没开。 */
    static volatile ChatInputActivity chatInput = null;

    public static void attachChatInput(ChatInputActivity a) {
        chatInput = a;
    }

    public static void detachChatInput(ChatInputActivity a) {
        if (chatInput == a) chatInput = null;
    }

    /** 输入代理的文本变化 → 回灌页面里的原版弹窗（显示与宽度自适应都交给上游组件）。 */
    public static void setChatDraft(final String text) {
        final PetService svc = INSTANCE;
        if (svc == null) return;
        final String t = text == null ? "" : text;
        svc.main.post(() -> svc.js("window.__petHost&&window.__petHost.chatDraft(" + JSONObject.quote(t) + ")"));
    }

    /** 输入法高度（物理像素）→ 页面把弹窗抬到键盘上方；0 = 收起。 */
    public static void setChatIme(final int px) {
        final PetService svc = INSTANCE;
        if (svc == null) return;
        final int p = Math.max(0, px);
        svc.main.post(() -> svc.js("window.__petHost&&window.__petHost.chatIme(" + p + ")"));
    }

    /** 输入代理结束（返回键/取消）→ 页面把弹窗一起关掉，两边状态一致。 */
    public static void chatInputClosed(final ChatInputActivity a) {
        if (chatInput == a) chatInput = null;
        final PetService svc = INSTANCE;
        if (svc == null) return;
        svc.main.post(() -> svc.js("window.__petHost&&window.__petHost.chatClosed()"));
    }

    public static boolean isRunning() {
        return running;
    }

    private void log(String s) {
        Log.i(TAG, s);
        synchronized (logs) {
            logs.add(s);
            while (logs.size() > 120) logs.removeFirst();
        }
    }

    @Override
    public void onCreate() {
        INST = this;
        // 崩溃落盘：进程被 kill 之后 /dump 也没了，只能靠这个文件回溯
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("[").append(new java.util.Date()).append("] app=").append(APP_VER)
                        .append(" thread=").append(t.getName()).append("\n").append(e).append("\n");
                StackTraceElement[] st = e.getStackTrace();
                for (int i = 0; i < st.length && i < 40; i++) sb.append("  at ").append(st[i]).append("\n");
                sb.append("--- recent logs ---\n");
                synchronized (logs) {
                    for (String l : logs) sb.append(l).append("\n");
                }
                java.io.File f = new java.io.File("/sdcard/.dsh-phone/pet-crash.txt");
                java.io.File dir = f.getParentFile();
                if (dir != null) dir.mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                    fos.write(sb.toString().getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        super.onCreate();
        INSTANCE = this;
        running = true;
        // 装完 App 就自带她的配置：后台把 assets 里那份 PUT 给 dsh-pet（dsh 没起来就重试几轮）
        new Thread(() -> {
            for (int i = 0; i < 12; i++) {
                if (applyBundledConfigOnce()) return;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "pet-config").start();
        main = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        refreshMetrics();
        log("screen=" + screenW + "x" + screenH + " density=" + density
                + " area=" + areaW + "x" + areaH + "@" + areaTop);

        // 起窗之前先算出该用哪个模型：无障碍已经连上就直接进 A（不依赖任何配置文件）
        a11yOn = wantA11y();
        if (a11yOn) {
            WindowManager aw = PetA11y.wm();
            if (aw != null) wm = aw;
            else a11yOn = false;
        }
        startForeground(1, notification());
        main.post(this::createWindows);
        startWatchdog();
        armPagePing();
        new Thread(this::loopStatus, "pet-status").start();
        new Thread(new DebugServer(), "pet-debug").start();
        new Thread(this::tuneLoop, "pet-tune").start();
    }

    /**
     * 页面存活探测（宿主侧，v6.7）。
     *
     * 为什么不再只信页面里的心跳：页面那边是 `setInterval(beat, 5000)`，实测会被拖慢/停掉
     * （真机日志里见过连续 12 次「silent 60s -> reload page」，也就是每 60 秒白白重载一次页面 ——
     *  重载瞬间旧画面还在、新页面还没画出来，用户看到的就是「有时会有两个她」「有时半透明」）。
     * `evaluateJavascript` 的回调由宿主侧触发，只要 JS 引擎还能跑就会回来，不受页面定时器节流影响。
     */
    private void armPagePing() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                pingPage();
                main.postDelayed(this, PING_MS);
            }
        }, PING_MS);
    }

    private void pingPage() {
        final WebView w = web;
        if (w == null) return;
        try {
            w.evaluateJavascript("1", value -> {
                if (w == web) {
                    long now = System.currentTimeMillis();
                    pingOkAt = now;
                    pingN++;
                    lastPageAliveAt = now;
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private int dimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /**
     * 读真实屏幕 + 系统栏尺寸。
     *
     * 2026-10-29：以前这段只在 onCreate 里跑一次，结果**旋转之后所有几何都还是竖屏的数** ——
     * 页面拿到的 workArea 是 1272×2603，横屏（2800×1272）下她就只能在一条竖着的窄带里走：
     * 往右走不到真正的屏幕右边，往下的部分又跑到屏幕外面去了。用户原话「真正屏幕区域有部分无法前往」。
     *
     * 现在这个方法会在 onConfigurationChanged 里重跑。insets 优先（API 30+，横屏导航栏会换边，
     * 「dimen 读到的数」在那种情况下是错的），拿不到就退回老口径。
     */
    private void refreshMetrics() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            Display d = wm.getDefaultDisplay();
            d.getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            density = dm.density;
        } catch (Throwable t) {
            log("metrics: realMetrics failed " + t);
        }
        int sb = dimen("status_bar_height");
        int nb = dimen("navigation_bar_height");
        boolean landscape = screenW > screenH;
        // 兜底口径：竖屏时导航栏在下方；横屏时按「导航栏在侧边」保守处理 —— 两边都让出 nb，
        // 宁可少一格（她走不到最边上 16dp），也不能让她走到导航栏/屏幕外面去。
        areaTop = sb;
        if (landscape) {
            areaW = screenW - (nb > 0 ? nb : 0);
            areaH = screenH - sb - (nb > 0 ? nb : 0);
        } else {
            areaW = screenW;
            areaH = screenH - sb - (nb > 0 ? nb : 0);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.WindowMetrics raw = wm.getMaximumWindowMetrics();
                android.graphics.Rect b = raw.getBounds();
                if (b.width() > 0 && b.height() > 0) {
                    screenW = b.width();
                    screenH = b.height();
                }
                android.view.WindowInsets in = wm.getCurrentWindowMetrics().getWindowInsets();
                android.graphics.Insets si = in.getInsets(android.view.WindowInsets.Type.statusBars());
                android.graphics.Insets ni = in.getInsets(android.view.WindowInsets.Type.navigationBars());
                // ★ 只有真的读到数才采信。这台机器的 targetSdk 是 28，insets 走 legacy 口径，
                //   实测**全是 0**（v6.4 就踩了这个坑：areaTop 变 0、areaH 变整屏，她走进了状态栏底下）。
                if (si.top + si.bottom + ni.top + ni.bottom + ni.left + ni.right > 0) {
                    areaTop = Math.max(0, si.top);
                    areaW = Math.max(320, screenW - ni.left - ni.right);
                    areaH = Math.max(320, screenH - areaTop - si.bottom - ni.top - ni.bottom);
                } else {
                    log("metrics: insets all zero (legacy target), keep dimen fallback");
                }
            } catch (Throwable t) {
                log("metrics: insets failed, fallback to dimen " + t);
            }
        }
    }

    /**
     * 系统配置变了（旋转 / 分屏 / 显示尺寸变化）：重读尺寸并把新几何推给页面。
     *
     * 为什么推而不是重建窗口：重建 = 整页重载，她会闪一下、位置也重置；
     * 页面那边 `applyDeskGeometry()` + `relayout()` 本来就是「显示器变了」的官方入口
     * （desktop 版走 petBridge.onDisplays 同一条路），热挂上去就行。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        final int ow = screenW, oh = screenH;
        try {
            refreshMetrics();
        } catch (Throwable t) {
            log("config change: metrics failed " + t);
        }
        if (ow == screenW && oh == screenH) return;
        log("screen changed: " + ow + "x" + oh + " -> " + screenW + "x" + screenH
                + " area=" + areaW + "x" + areaH + "@" + areaTop);
        if (main == null) return;
        main.post(this::onScreenResized);
        // 系统栏的 insets 偶尔比 configuration 回调晚一拍（实测横屏第一次拿到的是竖屏的 top），
        // 700ms 后再读一次、再推一次：几何一样就是空操作，不一样正好补上。
        main.postDelayed(() -> {
            try {
                refreshMetrics();
            } catch (Throwable ignore) {
            }
            onScreenResized();
        }, 700);
    }

    /** 真正把新几何落到窗口和页面上（主线程）。 */
    private void onScreenResized() {
        if (!running) return;
        try {
            // 全屏模型（模式 A / 固定窗）的窗口大小是写死在 LayoutParams 里的，必须跟着改
            if (lpRender != null && web != null && a11yOn) {
                lpRender.width = areaW;
                lpRender.height = areaH;
                lpRender.y = areaTop;
                try {
                    wm.updateViewLayout(web, lpRender);
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            log("resize window failed " + t);
        }
        pushGeometry();
        reportMetrics();
    }

    /**
     * 把当前工作区几何推给页面（物理像素；页面自己 ÷scale）。
     *
     * 和 URL 里的 workAreaX/Y/W/H 是同一套语义：窗口原点 = 工作区原点，
     * 所以 hull 用 (0, areaTop, areaW, areaH)。areas/workAreas/panels 都给同一块矩形，
     * 手机上就一块屏，`AREAS` 的并集判定不需要更复杂的多边形。
     */
    private void pushGeometry() {
        String geo = "{\"hull\":{\"x\":0,\"y\":" + areaTop + ",\"width\":" + areaW + ",\"height\":" + areaH + "},"
                + "\"areas\":[{\"x\":0,\"y\":" + areaTop + ",\"width\":" + areaW + ",\"height\":" + areaH + "}],"
                + "\"workAreas\":[{\"x\":0,\"y\":" + areaTop + ",\"width\":" + areaW + ",\"height\":" + areaH + "}],"
                + "\"panels\":[{\"x\":0,\"y\":0,\"width\":" + screenW + ",\"height\":" + screenH + "}],"
                + "\"primaryIndex\":0}";
        js("(function(){try{"
                + "if(typeof applyDeskGeometry!=='function'){window.__petHostGeoMissing=1;return;}"
                + "applyDeskGeometry(" + geo + ");"
                + "if(typeof sprites!=='undefined'&&sprites){for(var i=0;i<sprites.length;i++){"
                + "try{sprites[i].relayout&&sprites[i].relayout();}catch(e){}}}"
                + "window.__petHostView=[" + areaW + "," + areaH + "," + areaTop + "];"
                + "}catch(e){window.__petHostGeoErr=String(e);}})()");
        log("geometry pushed area=" + areaW + "x" + areaH + "@" + areaTop + " screen=" + screenW + "x" + screenH);
    }

    private Notification notification() {
        String ch = "pet";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(ch) == null) {
                NotificationChannel c = new NotificationChannel(ch, "桌宠", NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        return b.setContentTitle("DSH 桌宠").setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pi).setOngoing(true).build();
    }

    // ---------------- 窗口 ----------------

    private int winFlags(boolean passThrough) {
        int f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (passThrough) f |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return f;
    }

    private int formatFor(String f) {
        if ("opaque".equals(f)) return PixelFormat.OPAQUE;
        if ("transparent".equals(f)) return PixelFormat.TRANSPARENT;
        if ("rgba8888".equals(f)) return PixelFormat.RGBA_8888;
        return PixelFormat.TRANSLUCENT;
    }

    /** PRIVATE_FLAG_TRUSTED_OVERLAY(1<<9)：告诉 ROM「这块是全屏透明浮层，别当遮挡弹窗」。
     *  targetSdk 28 不受 non-SDK 限制，反射可写；官方 2.8 的宿主里也有同款（他们用来量合成折扣）。 */
    private static void setTrustedFlag(WindowManager.LayoutParams lp, boolean on) {
        if (lp == null) return;
        try {
            java.lang.reflect.Field f = WindowManager.LayoutParams.class.getField("privateFlags");
            int v = f.getInt(lp);
            v = on ? (v | 0x200) : (v & ~0x200);
            f.setInt(lp, v);
        } catch (Throwable t) {
            Log.w("PetHost", "privateFlags: " + t);
        }
    }

    /** trusted 标志默认关闭：实测打上之后这台 ROM 反而把她压暗了（用户："她透明了"）。 */
    private volatile boolean winTrusted = false;

    private void applyViewParams() {
        if (web == null) return;
        try {
            web.setAlpha((float) winViewAlpha);
        } catch (Throwable ignore) {
        }
        try {
            if ("hw".equals(winLayer)) web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            else if ("sw".equals(winLayer)) web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            else web.setLayerType(View.LAYER_TYPE_NONE, null);
        } catch (Throwable ignore) {
        }
    }

    /** 按当前 win* 参数重建渲染窗（触摸窗一并重建，保证层级在上面）。 */
    private void rebuildRenderWindow() {
        if (web == null) return;
        winGen++;
        log("rebuild window format=" + winFormat + " hw=" + winHw + " va=" + winViewAlpha + " layer=" + winLayer);
        removeNow(web);
        removeNow(touch);
        try {
            int type = winTypeInt();
            int f;
            if (a11yOn) {
                // 模式 A：全屏显示窗永远穿透（无障碍层不会被压暗），触摸全归触摸窗
                f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else if ("touchThenPass".equals(winFlagSet)) {
                // 先按"可触摸"建窗（ROM 对穿透窗会压 alpha），建完再翻回穿透
                f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            } else if ("touchable".equals(winFlagSet)) {
                // 可触摸版：某些 ROM 会给"点击穿透"的悬浮窗压 alpha
                f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            } else if ("minimal".equals(winFlagSet)) {
                f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else if ("noNoLimits".equals(winFlagSet)) {
                f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            if (winHw) f |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            // ★ 窗口固定铺满整个工作区，永不改变大小：她只在这个固定坐标系里移动。
            //   菜单放不放得下改由「可触摸区域」决定，不再靠改窗口大小（改窗口会有 2~3 帧错位 → 回弹）。
            lpRender = new WindowManager.LayoutParams(a11yOn ? areaW : 8, a11yOn ? areaH : 8,
                    type, f, formatFor(winFormat));
            lpRender.gravity = Gravity.TOP | Gravity.LEFT;
            lpRender.x = 0;
            lpRender.y = areaTop;
            lpRender.alpha = (float) winWinAlpha;
            lpRender.dimAmount = 0f;
            lpRender.windowAnimations = 0;
            applyViewParams();
            setTrustedFlag(lpRender, winTrusted);
            try {
                wm.addView(web, lpRender);
                installTouchRegion();
            } catch (Throwable boom) {
                log("addView failed (" + winType + "/" + winFlagSet + "): " + boom);
                removeNow(web);
                if (!"overlay".equals(winType)) log("type " + winType + " 不被允许，永久退回 overlay");
                // 兜底：退回最保险的组合，绝不让窗口消失
                winType = "overlay";
                winFlagSet = "default";
                int safeType = Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                int safeF = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                lpRender = new WindowManager.LayoutParams(areaW, areaH, safeType, safeF, PixelFormat.TRANSLUCENT);
                lpRender.gravity = Gravity.TOP | Gravity.LEFT;
                lpRender.x = 0;
                lpRender.y = areaTop;
                lpRender.alpha = (float) winWinAlpha;
                lpRender.windowAnimations = 0;
                wm.addView(web, lpRender);
            }

            int tf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (winHw) tf |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            lpTouch = new WindowManager.LayoutParams(1, 1, lpRender.type, tf, PixelFormat.TRANSLUCENT);
            lpTouch.gravity = Gravity.TOP | Gravity.LEFT;
            setTrustedFlag(lpTouch, winTrusted);
            wm.addView(touch, lpTouch);
            actValid = false;
            syncProbe();
            loadPetPage();
            main.postDelayed(this::forceAlphaOne, 350);
            main.postDelayed(this::forceAlphaOne, 1200);
            if ("touchThenPass".equals(winFlagSet)) main.postDelayed(this::applyPassThrough, 600);
            if (a11yOn) armA11ySelfCheck(); // 重建后也要能自己回退，不然坏在 A 里没人救

        } catch (Throwable t) {
            log("rebuild failed " + t);
        }
    }

    /** 页面级看门狗：静默 60s reload、180s 整窗重建。渲染进程被 ROM 杀掉时，窗口还在但宠物没了。 */
    private void startWatchdog() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!running) return;
                    long now = System.currentTimeMillis();
                    // 屏幕关着的时候她本来就看不见，WebView 也可能被系统挂起 —— 这时候去 reload
                    // 只会白白重建页面（回来时正好撞上"旧画面还在、新页面还没画"的那一刻）。
                    if (!screenOn()) {
                        if (lastPageAliveAt > 0) lastPageAliveAt = now;
                        main.postDelayed(this, WATCH_MS);
                        return;
                    }
                    if (lastPageAliveAt > 0) {
                        long silent = now - lastPageAliveAt;
                        if (silent > SILENT_RECREATE_MS) {
                            lastPageAliveAt = now;
                            log("watchdog: silent " + silent + "ms -> recreate window");
                            recreateWindows();
                        } else if (silent > SILENT_RELOAD_MS) {
                            lastPageAliveAt = now;
                            log("watchdog: silent " + silent + "ms -> reload page");
                            loadPetPage();
                        }
                    }
                } catch (Throwable ignore) {
                }
                main.postDelayed(this, WATCH_MS);
            }
        }, WATCH_MS);
    }

    /** 彻底换一套窗口 + WebView（渲染进程死后那个 WebView 已经不可用，必须新建）。 */
    private void recreateWindows() {
        try {
            removeNow(web);
            removeNow(touch);
        } catch (Throwable ignore) {
        }
        try {
            if (web != null) web.destroy();
        } catch (Throwable ignore) {
        }
        web = null;
        touch = null;
        lpRender = null;
        lpTouch = null;
        actValid = false;
        touchRegionKey = "";
        lastPageAliveAt = 0;
        createWindows();
    }

    /** 切换窗口模型（模式 A / B）。两种模式用的 WindowManager 不同，换之前必须先把窗摘干净。 */
    private boolean wantA11y() {
        if (a11yBlocked) return false;
        if ("board".equals(winMode)) return false;
        return PetA11y.isReady(); // auto 与 a11y 都需要无障碍真的连上
    }

    /** 无障碍连上/断开：只在"该不该用 A"真的变了时才重排窗口（否则会白白重载页面）。 */
    private void a11yModeChanged() {
        boolean want = wantA11y();
        if (want == a11yOn) {
            log("a11y state changed, mode stays " + (a11yOn ? "A" : "B"));
            return;
        }
        switchMode(want);
    }

    private void applyWinMode() {
        a11yBlocked = false; // 调参/自检重试时允许再试一次
        boolean want = wantA11y();
        if (!want && !a11yOn && !PetA11y.isReady()) promptA11yOnce();
        if (want != a11yOn) {
            switchMode(want);
            return;
        }
        rebuildRenderWindow();
    }

    /**
     * 用户点名要模式 A（win.mode = a11y），但宿主这份无障碍服务还没开：
     * 直接把「设置 → 无障碍」拉起来一次，让他少走几步。
     *
     * 只做一次（每次装机/每进程一次）：调参循环 3s 一轮，不加锁会反复弹设置页。
     * 用户在那个页面里把「DSH 桌宠悬浮窗」打开之后：
     *   PetA11y.onServiceConnected → PetService.onA11yChanged → switchMode(true)，
     * 窗口会自动换成「全屏无障碍层 + 独立触摸窗」，不用再改任何配置。
     */
    private void promptA11yOnce() {
        if (a11yPrompted || !"a11y".equals(winMode)) return;
        a11yPrompted = true;
        log("a11y 未开启：拉起设置→无障碍（打开「DSH 桌宠悬浮窗」即可，只提示这一次）");
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            log("open a11y settings failed " + t);
        }
    }

    private void switchMode(boolean want) {
        log("win mode -> " + (want ? "a11y(模式A)" : "board(模式B)"));
        removeNow(web);
        removeNow(touch);
        try { if (web != null) web.destroy(); } catch (Throwable ignore) {}
        web = null;
        touch = null;
        WindowManager aw = want ? PetA11y.wm() : null;
        if (want && aw == null) { // 无障碍刚断开：别硬上，标记本次进程不再试
            a11yBlocked = true;
            want = false;
        }
        a11yOn = want;
        wm = (want && aw != null) ? aw : (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        actValid = false;
        touchRegionKey = "";
        createWindows();
    }

    /** 模式 A 自检：12s 内页面既没心跳也没报过身体矩形 → 说明这套窗口里她根本没跑起来，
     *  自动退回模式 B（否则用户看到的是一块全屏透明层，宠物不知去向）。 */
    private void armA11ySelfCheck() {
        final int gen = ++a11yGen;
        main.postDelayed(() -> {
            try {
                if (gen != a11yGen || !a11yOn) return;
                if (lastPageAliveAt > 0 && bodyValid) {
                    log("a11y self-check ok (alive, body " + Math.round(bodyRelW) + "x" + Math.round(bodyRelH) + ")");
                    return;
                }
                log("a11y self-check FAILED (alive=" + (lastPageAliveAt > 0) + " body=" + bodyValid
                        + ") -> fallback to board");
                a11yBlocked = true;
                if (a11yOn) switchMode(false);
            } catch (Throwable ignore) {
            }
        }, 12000);
    }

    /** 无障碍服务连上/断开时由 PetA11y 调用。 */
    static void onA11yChanged() {
        PetService s = INST;
        if (s != null && s.main != null) s.main.post(s::a11yModeChanged);
    }

    /** 同步摘窗（removeView 是异步的，紧接着 addView 会报"已经加过了"）。 */
    /**
     * 同步摘窗（removeView 是异步的，紧接着 addView 会报"已经加过了"）。
     *
     * v6.7：不能只用"当前那个 WindowManager"。模式 A↔B 切换、或无障碍服务被系统杀掉时，
     * 「这块窗当初是谁加的」和「现在手上的 wm 是谁」可能不是同一个 —— 摘不掉就会在屏幕上
     * 留下一块旧画面的全屏窗，用户看到的就是「有时候有两个她」/「有时候发虚」。
     * 所以三个都试一遍：当前 wm、无障碍 wm、系统 wm。
     */
    private void removeNow(View v) {
        if (v == null) return;
        WindowManager[] list = new WindowManager[]{
                wm, safeA11yWm(), (WindowManager) getSystemService(Context.WINDOW_SERVICE)
        };
        for (WindowManager m : list) {
            if (m == null) continue;
            try {
                m.removeViewImmediate(v);
                return;
            } catch (Throwable ignore) {
            }
            try {
                m.removeView(v);
                return;
            } catch (Throwable ignore) {
            }
        }
        log("removeNow: 三个 WindowManager 都摘不掉这块窗（旧的可能还留在屏幕上）");
    }

    /** 屏幕是不是亮着（熄屏时不做页面健康检查 / 不重载）。 */
    private boolean screenOn() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private WindowManager safeA11yWm() {
        try {
            return PetA11y.wm();
        } catch (Throwable t) {
            return null;
        }
    }

    private int winTypeInt() {
        if ("phone".equals(winType)) return WindowManager.LayoutParams.TYPE_PHONE;
        if ("system-alert".equals(winType)) return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        if ("application".equals(winType)) return WindowManager.LayoutParams.TYPE_APPLICATION;
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** 纯色探针：一块普通 View（左黑右白），用来判断"窗口合成"和"WebView 输出"哪个被压了。 */
    private static class ProveView extends View {
        ProveView(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            int w = getWidth(), h = getHeight();
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(0xFF000000);
            canvas.drawRect(0, 0, w / 2f, h, p);
            p.setColor(0xFFFFFFFF);
            canvas.drawRect(w / 2f, 0, w, h, p);
        }
    }

    private void syncProbe() {
        try {
            if (winProbe) {
                if (probeView == null) {
                    probeView = new ProveView(this);
                    lpProbe = new WindowManager.LayoutParams(240, 120, winTypeInt(),
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT);
                    lpProbe.gravity = Gravity.TOP | Gravity.LEFT;
                    lpProbe.x = 40;
                    lpProbe.y = 40;
                    lpProbe.alpha = 1f;
                    wm.addView(probeView, lpProbe);
                    log("probe window added");
                }
            } else if (probeView != null) {
                wm.removeView(probeView);
                probeView = null;
                lpProbe = null;
                log("probe window removed");
            }
        } catch (Throwable t) {
            log("probe failed " + t);
        }
    }

    private void createWindows() {
        winGen++;
        log("createWindows gen=" + winGen + " a11y=" + a11yOn + " area=" + areaW + "x" + areaH + "@" + areaTop);
        try {
            int type = a11yOn
                    ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : (Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE);

            web = new WebView(this);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setUseWideViewPort(false);
            s.setLoadWithOverviewMode(false);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            applyViewParams();
            web.setBackgroundColor(Color.TRANSPARENT);
            // 对话输入要键盘：WebView 必须是可聚焦的 view（窗口本身的可聚焦性由 setChatOpen 控制）
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.setVerticalScrollBarEnabled(false);
            web.setHorizontalScrollBarEnabled(false);
            web.setOverScrollMode(View.OVER_SCROLL_NEVER);
            // 注意：**不要**在这里 setLayerType(HARDWARE)。WebView 已经是硬件加速（走窗口的 surface），
            // 再套一层硬件层会让透明视频的 alpha 被预乘两次 → 宠物整体发灰半透明。
            web.addJavascriptInterface(new Bridge(), "PetHost");
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage cm) {
                    log("js: " + cm.message());
                    return true;
                }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                    v.evaluateJavascript("window.petBridge=window.PetHost;window.__petHostBound=1;", null);
                }

                /**
                 * ★ 补丁层由 **APK 自己发**，不再依赖插件目录里那份。
                 *
                 * 页面模板 /dsh-pet-android/desktop/index.html 里写着 `<script src="./host-shim.js">`，
                 * 那个请求就是这里拦下来的：直接回 assets/host-shim.js 的内容。
                 * 好处是「装哪个 APK，就跑哪一版补丁」——插件被更新/被删/容器文件丢了，
                 * 她的行为也不会退回旧版。插件那边保留同名文件只是为了在普通浏览器里预览。
                 */
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                    try {
                        String u = req.getUrl() == null ? "" : req.getUrl().toString();
                        if (u.indexOf("/dsh-pet-android/desktop/host-shim.js") >= 0) {
                            String js = readAssetText("host-shim.js");
                            if (js != null) {
                                shimFrom = "asset";
                                log("shim <- APK assets (" + js.length() + " bytes)");
                                return new WebResourceResponse("application/javascript", "utf-8",
                                        new java.io.ByteArrayInputStream(js.getBytes("UTF-8")));
                            }
                        }
                    } catch (Throwable t) {
                        log("shim intercept failed " + t);
                    }
                    return null;
                }

                @Override
                public void onPageFinished(WebView v, String url) {
                    pageLoads++;
                    log("page loaded #" + pageLoads);
                    // extraFrom 只对**当前这一页**有意义：每次加载先假设"插件那份会跑"，
                    // 只有真的没跑（下面问出来是 0）才置 asset-inject。否则旧值会赖着不走，
                    // 让 verify.js 把"这次其实是插件装的"误报成兜底注入（2026-10-29 实测踩过）。
                    extraFrom = "plugin";
                    // APK 层的显示口径：菜单右侧那个「Ctrl+左键」提示一律不显示
                    // （用户要求；写在这里而不是页面里，是为了换插件版本/换宿主页面也照样生效）
                    v.evaluateJavascript(
                            "(function(){try{if(document.getElementById('pet-host-ui-css'))return;" +
                            "var s=document.createElement('style');s.id='pet-host-ui-css';" +
                            "s.textContent='.dsh-pet-menu-hint{display:none !important}';" +
                            "(document.head||document.documentElement).appendChild(s);}catch(e){}})()",
                            null);
                    // 兜底：万一页面模板里没有那行 <script>（插件换过模板），这里补注入。
                    // 分两次发：先问一句装没装，没装才把整份代码推过去。
                    v.evaluateJavascript("(window.__petHostShimInstalled?'1':'0')", new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String s) {
                            if (s == null || s.indexOf("0") < 0) return;
                            String js = readAssetText("host-shim.js");
                            if (js == null) return;
                            shimFrom = "asset-inject";
                            log("shim <- APK assets injected (" + js.length() + " bytes)");
                            v.evaluateJavascript(js, null);
                        }
                    });
                    // 同上，第二份补丁 host-extra.js（菜单「打开 DSH」/ 藏 Ctrl+左键提示 / 禁快捷键）。
                    // 设计是**插件优先、assets 兜底**：插件目录里有那份就让它跑（可以热更），
                    // 没有（新设备 / 容器文件丢了 / 404）才推 assets 里这份 —— 这正是 HANDOFF §0.11
                    // 「配置零外部存储依赖，装好 APK 就是最佳状态」要的效果。
                    v.evaluateJavascript("(window.__petExtraInstalled?'1':'0')", new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String s) {
                            if (s == null || s.indexOf("0") < 0) return;
                            String js = readAssetText("host-extra.js");
                            if (js == null) return;
                            extraFrom = "asset-inject";
                            log("extra <- APK assets injected (" + js.length() + " bytes)");
                            v.evaluateJavascript(js, null);
                        }
                    });
                    main.postDelayed(PetService.this::reportMetrics, 2600);
                }

                @Override
                public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                    log("render process gone (crash=" + (d != null && d.didCrash()) + ") -> rebuild");
                    main.postDelayed(PetService.this::recreateWindows, 1200);
                    return true; // 自己接住，别让系统连服务一起杀
                }

                @Override
                public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                    if (r != null && r.isForMainFrame()) {
                        lastError = "load: " + e.getDescription() + " " + r.getUrl();
                        log("ERR " + lastError);
                        main.postDelayed(PetService.this::loadPetPage, 6000);
                    }
                }
            });

            // ★ 窗口固定铺满整个工作区（见 rebuildRenderWindow 的说明）
            // 模式 A：显示窗从一开始就是「整块工作区 + 只读」；模式 B：8×8 起，由页面撑开
            lpRender = a11yOn
                    ? new WindowManager.LayoutParams(areaW, areaH, type,
                        winFlags(true) | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT)
                    : new WindowManager.LayoutParams(8, 8, type, winFlags(true), PixelFormat.TRANSLUCENT);
            lpRender.gravity = Gravity.TOP | Gravity.LEFT;
            lpRender.alpha = (float) winWinAlpha;
            lpRender.dimAmount = 0f;
            lpRender.windowAnimations = 0;
            lpRender.x = 0;
            lpRender.y = areaTop;
            lpRender.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            setTrustedFlag(lpRender, winTrusted);
            wm.addView(web, lpRender);
            installTouchRegion();
            watchTouchRegion();

            touch = new TouchView(this);
            touch.setBackgroundColor(Color.TRANSPARENT);
            lpTouch = new WindowManager.LayoutParams(1, 1, type, winFlags(false), PixelFormat.TRANSLUCENT);
            lpTouch.gravity = Gravity.TOP | Gravity.LEFT;
            setTrustedFlag(lpTouch, winTrusted);
            wm.addView(touch, lpTouch);

            main.postDelayed(this::pollLocation, 120);
            if (a11yOn) armA11ySelfCheck();
            loadPetPage();
            main.postDelayed(this::forceAlphaOne, 350);
            main.postDelayed(this::forceAlphaOne, 1200);
        } catch (Throwable t) {
            lastError = "create: " + t;
            log("FATAL " + t);
            t.printStackTrace();
            if (a11yOn) { // 无障碍窗建不出来（没授权/被拒）→ 立刻退回模式 B，绝不留黑屏
                a11yBlocked = true;
                a11yOn = false;
                wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                main.postDelayed(this::createWindows, 800);
            }
        }
    }

    /** 每 90ms 读一次 WebView 在屏幕上的真实位置（系统会夹取窗口，lp.x/y 不等于实际位置）。 */
    private void pollLocation() {
        if (!running || web == null) return;
        try {
            int[] loc = new int[2];
            web.getLocationOnScreen(loc);
            if (!actValid || loc[0] != actX || loc[1] != actY) {
                if (actValid) log("window moved by system: lp=" + lpRender.x + "," + lpRender.y + " -> " + loc[0] + "," + loc[1]);
                actX = loc[0];
                actY = loc[1];
                actValid = true;
                js("window.__petHostWinXY=[" + actX + "," + actY + "]");
                placeTouch();
            }
        } catch (Throwable ignore) {
        }
        main.postDelayed(this::pollLocation, 90);
    }

    private void loadPetPage() {
        if (web == null) return;
        try {
            String q = "?configUrl=" + enc("http://127.0.0.1:3080/dsh-pet-7340/config")
                    + "&scale=" + density
                    + "&petIndex=0"
                    + "&workAreaX=0"
                    + "&workAreaY=" + areaTop
                    + "&workAreaW=" + areaW
                    + "&workAreaH=" + areaH
                    + "&openTarget=site"
                    + "&android=1";
            String url = "http://127.0.0.1:3080/dsh-pet-android/desktop/" + q;
            log("load " + url);
            lastError = "";
            web.loadUrl(url);
            if (a11yOn) {
                main.postDelayed(() -> js("window.__petHostFixedWin=1"), 600);
                main.postDelayed(() -> js("window.__petHostFixedWin=1"), 2000);
            }
        } catch (Throwable t) {
            log("loadUrl failed " + t);
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 页面 → 宿主的窗口控制原语（桌面端 preload.js 的等价物）。Binder 线程，只落值不搬窗。 */
    private class Bridge {
        @JavascriptInterface
        public void setBounds(double x, double y, double w, double h,
                              double bx, double by, double size, double bottomPad,
                              double vx, double vy, double marginL, double mT) {
            synchronized (lock) {
                reqX = (int) Math.round(x);
                reqY = (int) Math.round(y);
                reqW = Math.max(1, (int) Math.round(w));
                reqH = Math.max(1, (int) Math.round(h));
                boxX = bx;
                boxY = by;
                petSize = size;
                PetService.this.marginT = mT;
                PetService.this.marginR = marginL;
                if (!pending) {
                    pending = true;
                    main.post(PetService.this::flushBounds);
                }
            }
        }

        @JavascriptInterface
        public void setInteractive(boolean b) {
            interactiveFlag = b;
        }

        /** 保留的老接口：不再动窗口焦点（改可聚焦会让 ColorOS 弹"可能造成部分操作无响应"）。 */
        @JavascriptInterface
        public void setChatOpen(boolean open) {
        }

        /** 拉起隐形输入代理（2dp 透明窗口，只托住系统输入法）。 */
        @JavascriptInterface
        public void openChatInput() {
            launchChatInput(false, null);
        }

        /** 同上，但把页面弹窗里已有的草稿带过去（重开键盘时文字不能被清空）。 */
        @JavascriptInterface
        public void openChatInputWith(String draft) {
            launchChatInput(false, draft);
        }

        /** 页面关弹窗 → 把键盘也收掉（否则会留下"卡片没了、键盘还在"）。 */
        @JavascriptInterface
        public void closeChatInput() {
            main.post(() -> {
                ChatInputActivity a = chatInput;
                if (a != null) {
                    try {
                        a.finish();
                    } catch (Throwable ignore) {
                    }
                }
            });
        }

        /** 自检通道：拉起同一个 Activity，但不打字、直接发一句 → 验证 Activity→页面→/chat 整条链路。 */
        @JavascriptInterface
        public void chatSelfTest() {
            launchChatInput(true, null);
        }

        @JavascriptInterface
        public void setInputBusy(boolean b) {
            // 桌面端这个信号是给主进程「别把窗口翻回穿透」用的；Android 上它变成
            // 「现在有菜单/对话窗在用输入」——触摸窗必须临时扩到整窗，否则菜单上的点击
            // 根本到不了 App，会直接漏给下面的应用（「点菜单穿透过去了」）。
            if (b != inputBusy) {
                inputBusy = b;
                reqTouch();
            }
        }

        /** 页面 5s 一次的显式心跳（上游发呆时不改 setBounds，不能只靠桥调用判活）。 */
        @JavascriptInterface
        public void heartbeat() {
            lastPageAliveAt = System.currentTimeMillis();
        }

        @JavascriptInterface
        public void setBody(double x, double y, double w, double h) {
            lastPageAliveAt = System.currentTimeMillis();
            boolean was = bodyValid;
            float ox = bodyRelX, oy = bodyRelY, ow = bodyRelW, oh = bodyRelH;
            bodyRelX = (float) x;
            bodyRelY = (float) y;
            bodyRelW = (float) w;
            bodyRelH = (float) h;
            bodyValid = w > 0 && h > 0;
            reqTouch();
            if (!was || Math.abs(ox - bodyRelX) > 2 || Math.abs(oy - bodyRelY) > 2
                    || Math.abs(ow - bodyRelW) > 2 || Math.abs(oh - bodyRelH) > 2) {
                log("page body rect rel=" + Math.round(bodyRelX) + "," + Math.round(bodyRelY)
                        + " " + Math.round(bodyRelW) + "x" + Math.round(bodyRelH));
            }
        }

        @JavascriptInterface
        public void setInputRegions(String json) {
            inputRegions = json;
            reqTouch();
        }

        @JavascriptInterface
        /** 上游的 Ctrl+左键 / 「打开网站」都走这里。用户要求禁用快捷键 → 这里只记日志、不动作；
         *  真正要唤起 App 的是菜单项（页面那边改调 openDsh()）。 */
        public void openDshSite(String url) {
            log("openDshSite ignored (shortcut disabled): " + url);
        }

        /** 菜单项「打开 DSH」专用：把 DSH 本体切到前台（找不到才退回浏览器）。 */
        @JavascriptInterface
        public void openDsh() {
            openDshNow("");
        }

        private void openDshNow(String url) {
            try {
                Intent i = dshAppIntent();
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(i);
                    log("open dsh app: " + i.getPackage());
                    return;
                }
            } catch (Throwable t) {
                log("open dsh app failed " + t);
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                log("open site failed " + t);
            }
        }

        @JavascriptInterface
        public void reportFlight(String json) {
        }

        @JavascriptInterface
        public void reportCollide(String targetId, double vx, double vy) {
        }

        @JavascriptInterface
        public void log(String s) {
            PetService.this.log("page: " + s);
        }
    }

    /**
     * 身体命中区的屏幕矩形（物理像素）。优先用页面上报的 .pet-hit（窗口内坐标 + 真实窗口位置）；
     * 没收到时退回"舞台矩形"（余量偏移 + size×winH）——舞台是整只宠物的画布，比身体宽一倍多。
     */
    private int[] bodyScreen() {
        int wx = actValid ? actX : (lpRender == null ? 0 : lpRender.x);
        int wy = actValid ? actY : (lpRender == null ? 0 : lpRender.y);
        double winH;
        synchronized (lock) {
            winH = Math.max(1, reqH - marginT - marginR);
        }
        if (bodyValid) {
            return new int[]{
                    (int) Math.round(wx + bodyRelX + tuneOffX),
                    (int) Math.round(wy + bodyRelY + tuneOffY),
                    Math.max(1, (int) Math.round(bodyRelW + tuneExtraW)),
                    Math.max(1, (int) Math.round(bodyRelH + tuneExtraH))};
        }
        // 兜底：舞台矩形。**不能**用 boxX/boxY —— 那是"包围盒在工作区的坐标"，不是窗口内偏移，
        // 早先误用它当余量，命中区整体偏左下，且她越往右下走偏得越多。这里用余量。
        return new int[]{
                (int) Math.round(wx + marginR + tuneOffX),
                (int) Math.round(wy + marginT + tuneOffY),
                Math.max(1, (int) Math.round(petSize + tuneExtraW)),
                Math.max(1, (int) Math.round(winH + tuneExtraH))};
    }

    /** 请求在主线程重摆一次触摸窗（桥的方法都在 Binder 线程，不能直接动 View/WindowManager）。 */
    /** DSH 本体的包名（Tdsh 移植就是 com.dshmobile.probe；换个移植也能按前缀找到）。 */
    static final String DSH_PKG = "com.dshmobile.probe";

    private Intent dshAppIntent() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent i = pm.getLaunchIntentForPackage(DSH_PKG);
            if (i != null) return i;
            for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(0)) {
                if (pi.packageName.startsWith("com.dshmobile")) {
                    Intent j = pm.getLaunchIntentForPackage(pi.packageName);
                    if (j != null) return j;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private void reqTouch() {
        main.post(PetService.this::placeTouch);
    }

    /** 把窗口 alpha 硬拉回 1：某些系统会给新建悬浮窗跑淡入动画，若被打断会停在中间值。 */
    /** 建窗后才翻成穿透：赌 ROM 只在建窗那一刻判定"要不要压暗"。 */
    private void applyPassThrough() {
        try {
            if (web == null || lpRender == null) return;
            lpRender.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lpRender.alpha = 1f;
            wm.updateViewLayout(web, lpRender);
            log("switched to pass-through, flags=" + lpRender.flags);
            main.postDelayed(this::forceAlphaOne, 300);
        } catch (Throwable t) {
            log("pass-through switch failed " + t);
        }
    }

    private void forceAlphaOne() {
        try {
            if (web != null && lpRender != null) {
                lpRender.alpha = 1f;
                web.setAlpha(1f);
                wm.updateViewLayout(web, lpRender);
            }
        } catch (Throwable ignore) {
        }
    }

    private void flushBounds() {
        synchronized (lock) {
            pending = false;
        }
        if (web == null || lpRender == null) return;
        if (a11yOn) { placeTouch(); return; } // 模式 A：显示窗钉死在整块工作区，只摆触摸窗
        try {
            lpRender.x = reqX;
            lpRender.y = reqY;
            lpRender.width = reqW;
            lpRender.height = reqH;
            wm.updateViewLayout(web, lpRender);
            placeTouch();
        } catch (Throwable t) {
            log("flush failed " + t);
        }
    }

    /** 触摸窗 = 宠物身体 ∪ 页面上报的可点控件（菜单 / 对话框 / 可展开气泡），屏幕物理像素。 */
    private void placeTouch() {
        if (touch == null || lpTouch == null || lpRender == null) return;
        int[] body = bodyScreen();
        double minX = body[0], minY = body[1];
        double maxX = body[0] + body[2], maxY = body[1] + body[3];

        String json = inputRegions;
        boolean hasRects = false;
        boolean dragging = false;
        if (json != null && json.length() > 4) {
            try {
                JSONObject o = new JSONObject(json);
                dragging = o.optBoolean("dragging", false);
                JSONArray rects = o.optJSONArray("rects");
                if (rects != null) {
                    for (int i = 0; i < rects.length(); i++) {
                        JSONObject r = rects.optJSONObject(i);
                        if (r == null) continue;
                        double x = r.optDouble("x", 0) + (actValid ? actX : lpRender.x);
                        double y = r.optDouble("y", 0) + (actValid ? actY : lpRender.y);
                        double w = r.optDouble("width", 0);
                        double h = r.optDouble("height", 0);
                        if (w <= 0 || h <= 0) continue;
                        hasRects = true;
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x + w);
                        maxY = Math.max(maxY, y + h);
                    }
                }
            } catch (Throwable ignore) {
            }
        }

        // 菜单/对话开着时把触摸层铺满整窗（点菜单外面要能收起菜单）；**拖拽不铺**——
        // 拖拽态一旦卡住，铺满就等于把整块屏幕吃掉（窗口现在本来就是整个工作区）。
        if (inputBusy && !dragging) {
            // 菜单/对话窗/可展开气泡开着：直接铺满整个渲染窗。这些控件由页面自己排版，
            // 拿它的矩形去拼容易漏一块（漏哪块就「穿透」哪块），铺满最稳。
            int wx0 = actValid ? actX : lpRender.x;
            int wy0 = actValid ? actY : lpRender.y;
            minX = wx0;
            minY = wy0;
            maxX = wx0 + lpRender.width;
            maxY = wy0 + lpRender.height;
        }

        syncTouchRegion();
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        int w = Math.max(1, (int) Math.ceil(maxX - minX));
        int h = Math.max(1, (int) Math.ceil(maxY - minY));
        if (x == lpTouch.x && y == lpTouch.y && w == lpTouch.width && h == lpTouch.height) return;
        lpTouch.x = x;
        lpTouch.y = y;
        lpTouch.width = w;
        lpTouch.height = h;
        try {
            wm.updateViewLayout(touch, lpTouch);
        } catch (Throwable t) {
            log("touch layout failed " + t);
        }
    }

    // ---------------- 可触摸区域 ----------------

    /** 渲染窗＝固定铺满整个工作区（永不改大小），靠这个回调声明"哪些像素归本窗"。
     *  它仍然是一个**可触摸窗**（不带 FLAG_NOT_TOUCHABLE，所以不会被 ROM 压暗），
     *  但系统只会把落在 touchableRegion 里的触摸交给它，其余像素直接穿透给下面的 App。
     *  区域 = 她身体 ∪ 页面自报的可点控件（菜单/对话/可点气泡）。 */
    private void installTouchRegion() {
        if (web == null) return;
        if (a11yOn) return; // 模式 A：窗口本身不可触摸，触摸全走独立触摸窗
        try {
            web.getViewTreeObserver().addOnComputeInternalInsetsListener(
                    new ViewTreeObserver.OnComputeInternalInsetsListener() {
                        @Override
                        public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
                            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                            info.touchableRegion.set(computeTouchRegion());
                            regionSeen = true;
                        }
                    });
            log("touch region installed");
        } catch (Throwable t) {
            log("touch region failed: " + t);
        }
    }

    private volatile boolean regionSeen = false;

    /** 兜底：窗口铺满工作区之后，万一系统不认这个可触摸区域回调（老 ROM 上可能是这样），
     *  整块屏幕就都会被吃掉。等 4 秒还没被调用过，就把服务停掉——宁可她不出现，也不能让人用不了手机。 */
    private void watchTouchRegion() {
        if (a11yOn) return; // 模式 A 不装 insets，等不到回调，别触发"屏幕安全"自停
        final int gen = winGen;
        main.postDelayed(() -> {
            // ★ 到点再判一次：这 4 秒里可能已经切到模式 A / 换了窗口 —— 那时候"没有回调"是正常的，
            //   老代码只看排程那一刻的 a11yOn，结果切换后 4 秒必自杀（真机上就是"无障碍开几秒又关"）。
            if (gen != winGen || a11yOn) return;
            if (!regionSeen) {
                log("touch region callback never fired -> stop service (screen safety)");
                stopSelf();
            }
        }, 4000);
    }

    private Region computeTouchRegion() {
        Region r = new Region();
        try {
            int ox = actValid ? actX : (lpRender == null ? 0 : lpRender.x);
            int oy = actValid ? actY : (lpRender == null ? 0 : lpRender.y);
            int[] b = bodyScreen();
            r.union(new Rect(b[0] - ox, b[1] - oy, b[0] - ox + b[2], b[1] - oy + b[3]));
            String json = inputRegions;
            if (json != null && json.length() > 4) {
                JSONObject o = new JSONObject(json);
                JSONArray rs = o.optJSONArray("rects");
                if (rs != null) {
                    for (int i = 0; i < rs.length(); i++) {
                        JSONObject q = rs.optJSONObject(i);
                        if (q == null) continue;
                        int x = (int) Math.round(q.optDouble("x", 0));
                        int y = (int) Math.round(q.optDouble("y", 0));
                        int w = (int) Math.round(q.optDouble("width", 0));
                        int h = (int) Math.round(q.optDouble("height", 0));
                        if (w > 0 && h > 0) r.union(new Rect(x, y, x + w, y + h));
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        if (r.isEmpty()) r.set(0, 0, 1, 1); // 非空：空区域会被系统当成"整窗不可触摸"
        return r;
    }

    /** 区域变了才请求一次重算（ViewRootImpl 在下一趟 traversal 里读上面那个回调）。 */
    private void syncTouchRegion() {
        try {
            if (web == null) return;
            int[] b = bodyScreen();
            String k = b[0] + "," + b[1] + "," + b[2] + "," + b[3] + "|" + inputRegions;
            if (k.equals(touchRegionKey)) return;
            touchRegionKey = k;
            web.requestLayout();
        } catch (Throwable ignore) {
        }
    }

    // ---------------- 触摸 ----------------

    /** 拉起隐形输入代理（只托键盘）。selfTest=true 时 Activity 不等人打字，直接发一句（诊断用）。 */
    private void launchChatInput(final boolean selfTest, final String draft) {
        main.post(() -> {
            try {
                ChatInputActivity open = chatInput;
                if (!selfTest && open != null && !open.isFinishing()) {
                    // 已经开着：不清空文字，只把键盘重新拉起来（用户点卡片 = 想继续打字）
                    log("chat input already open -> show ime");
                    open.showImeAgain();
                    return;
                }
                Intent i = new Intent(PetService.this, ChatInputActivity.class);
                // MULTIPLE_TASK：开一个只放输入代理的新任务，别把 App 主界面一起带到前台
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                if (selfTest) i.putExtra("autotest", true);
                if (draft != null && draft.length() > 0) i.putExtra("draft", draft);
                startActivity(i);
                log("open chat input ok selfTest=" + selfTest + " draft=" + (draft == null ? 0 : draft.length()));
            } catch (Throwable t) {
                log("open chat input failed " + t);
            }
        });
    }

    private void setChatFocus(final boolean open) {
        main.post(() -> {
            try {
                if (web == null || lpRender == null) return;
                // 不再改窗口焦点：输入改由 ChatInputActivity 完成
                if (!open) hideIme();
            } catch (Throwable t) {
                log("chat focus failed " + t);
            }
        });
    }

    private void showIme() {
        try {
            if (web == null) return;
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            imm.showSoftInput(web, 0);
        } catch (Throwable t) {
            log("ime show failed " + t);
        }
    }

    private void hideIme() {
        try {
            if (web == null) return;
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
        } catch (Throwable t) {
        }
    }

    /** 首次启动把 App 自带的默认配置写进 dsh-pet 用户层（成功/已写过返回 true）。 */
    /** 内嵌配置只推一次；标记放 **App 私有目录**，不再依赖 /sdcard 上还留着什么。 */
    private String cfgMarker() {
        return new java.io.File(getFilesDir(), "pet-config-v1.applied").getAbsolutePath();
    }
    private static final String CFG_URL = "http://127.0.0.1:3080/dsh-pet-7340/config";

    private boolean applyBundledConfigOnce() {
        try {
            // 老标记（v5.5 及以前写在 /sdcard）也认，避免升级后把她已经调好的配置又覆盖一遍
            if (new java.io.File(cfgMarker()).exists()) return true;
            if (new java.io.File("/sdcard/.dsh-phone/pet-config-v1.applied").exists()) return true;
            String body = readAssetText("pet-config.jsonc");
            if (body == null || body.length() < 40) return false;
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(CFG_URL).openConnection();
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(10000);
            c.setRequestProperty("content-type", "application/json");
            c.getOutputStream().write(body.getBytes("UTF-8"));
            int code = c.getResponseCode();
            c.disconnect();
            if (code >= 200 && code < 300) {
                java.io.File m = new java.io.File(cfgMarker());
                java.io.File dir = m.getParentFile();
                if (dir != null) dir.mkdirs();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(m);
                fo.write(("applied " + System.currentTimeMillis()).getBytes("UTF-8"));
                fo.close();
                try { // 老位置也补一个，回退到旧版本时行为一致
                    java.io.File old = new java.io.File("/sdcard/.dsh-phone/pet-config-v1.applied");
                    java.io.File od = old.getParentFile();
                    if (od != null) od.mkdirs();
                    java.io.FileOutputStream f2 = new java.io.FileOutputStream(old);
                    f2.write(("applied " + System.currentTimeMillis()).getBytes("UTF-8"));
                    f2.close();
                } catch (Throwable ignore) {
                }
                log("bundled config applied (HTTP " + code + ")");
                return true;
            }
            log("bundled config rejected: HTTP " + code);
            return code == 400;
        } catch (Throwable t) {
            return false;
        }
    }

    private String readAssetText(String name) {
        java.io.InputStream in = null;
        try {
            in = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private void js(String code) {
        if (web == null) return;
        try {
            web.evaluateJavascript(code, null);
        } catch (Throwable t) {
            log("js failed " + t);
        }
    }

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (touchDown && !touchMoved) {
                js("window.__petHost&&window.__petHost.menu(" + downX + "," + downY + ")");
                consumed = true;
            }
        }
    };

    private class TouchView extends View {
        TouchView(Context c) {
            super(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = y;
                    touchDown = true;
                    touchMoved = false;
                    consumed = false;
                    if (inBody(x, y)) {
                        js("window.__petHost&&window.__petHost.down(" + x + "," + y + ")");
                        main.removeCallbacks(longPress);
                        main.postDelayed(longPress, 460);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!touchDown) return true;
                    if (Math.hypot(x - downX, y - downY) > 18f) {
                        if (!touchMoved) {
                            touchMoved = true;
                            main.removeCallbacks(longPress);
                        }
                        js("window.__petHost&&window.__petHost.move(" + x + "," + y + ")");
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    if (touchDown) {
                        touchDown = false;
                        if (!consumed) {
                            if (inBody(downX, downY)) {
                                js("window.__petHost&&window.__petHost.up(" + x + "," + y + ")");
                            } else {
                                js("window.__petHost&&window.__petHost.clickAt(" + downX + "," + downY + ")");
                            }
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    /** 触点是否落在宠物身体矩形内（身体 = 页面上报的包围盒；外扩一点，手指比光标粗）。 */
    /** 触点是否落在身体上（页面自报的 .pet-hit 矩形，外扩一点给手指容差） */
    private boolean inBody(float sx, float sy) {
        if (lpRender == null) return false;
        int[] b = bodyScreen();
        float pad = tunePad > 0 ? tunePad : 8f;
        return sx >= b[0] - pad && sx <= b[0] + b[2] + pad && sy >= b[1] - pad && sy <= b[1] + b[3] + pad;
    }

    /** 把页面真实度量回传到插件（/report），容器侧就能远程看见 CSS 口径到底是多少。 */
    private void reportMetrics() {
        new Thread(PetService.this::reportMetricsBlocking, "pet-metrics").start();
    }

    private void reportMetricsBlocking() {
        final String js = "(function(){try{var s=sprites[0];var h=s.hit.getBoundingClientRect();"
                + "var e=s.el.getBoundingClientRect();"
                + "return JSON.stringify({iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio,"
                + "vw:visualViewport?visualViewport.width:null,vs:visualViewport?visualViewport.scale:null,"
                + "hit:[Math.round(h.left),Math.round(h.top),Math.round(h.width),Math.round(h.height)],"
                + "el:[Math.round(e.left),Math.round(e.top),Math.round(e.width),Math.round(e.height)],"
                + "pos:[Math.round(s.pos.x),Math.round(s.pos.y)],margin:s.margin,size:s.size,winH:s.winH,"
                + "view:VIEW});}catch(err){return 'METRICS-ERR '+err.message}})()";
        final String out = evalJs(js);
        String host = "{\"appVersion\":\"" + APP_VER + "\",\"density\":" + density + ",\"win\":[" + (actValid ? actX : 0) + ","
                + (actValid ? actY : 0) + "," + (lpRender == null ? 0 : lpRender.width) + ","
                + (lpRender == null ? 0 : lpRender.height) + "],\"lp\":["
                + (lpRender == null ? 0 : lpRender.x) + "," + (lpRender == null ? 0 : lpRender.y) + "],"
                + "\"box\":[" + boxX + "," + boxY + "," + petSize + "],\"touch\":["
                + (lpTouch == null ? 0 : lpTouch.x) + "," + (lpTouch == null ? 0 : lpTouch.y) + ","
                + (lpTouch == null ? 0 : lpTouch.width) + "," + (lpTouch == null ? 0 : lpTouch.height) + "]}";
        postJson("{\"upstreamOk\":true,\"error\":" + JSONObject.quote("METRICS " + out + " HOST " + host) + "}");
    }

    private void postJson(String body) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:3080/dsh-pet-android/report").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable t) {
            log("post failed " + t);
        }
    }

    /** 每 3s 拉一次实时调参；reloadSeq 变了就重载页面。 */
    private void tuneLoop() {
        while (running) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        "http://127.0.0.1:3080/dsh-pet-android/desktop/tune").openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = r.readLine()) != null) sb.append(ln);
                r.close();
                c.disconnect();
                JSONObject o = new JSONObject(sb.toString());
                JSONObject t = o.optJSONObject("tune");
                if (t != null) {
                    tuneOffX = (float) t.optDouble("offsetX", 0);
                    tuneOffY = (float) t.optDouble("offsetY", 0);
                    tuneExtraW = (float) t.optDouble("extraW", 0);
                    tuneExtraH = (float) t.optDouble("extraH", 0);
                    tunePad = (float) t.optDouble("padPx", 0);
                    JSONObject w = t.optJSONObject("win");
                    if (w != null) {
                        // 只有插件真的读到了 pet-tune.json，才会回 win 对象；
                        // 没有这个文件 = 纯用 APK 内置默认（/dump 的 tuneFileSeen 可查）。
                        tuneFileSeen = true;
                        int apply = w.optInt("apply", -1);
                        String fmt = w.optString("format", winFormat);
                        boolean hw = w.optBoolean("hw", winHw);
                        double va = w.optDouble("viewAlpha", winViewAlpha);
                        winWinAlpha = w.optDouble("winAlpha", winWinAlpha);
                        boolean probeFlag = w.optBoolean("probe", winProbe);
                        String typeStr = w.optString("type", winType);
                        String flagStr = w.optString("flags", winFlagSet);
                        String layer = w.optString("layer", winLayer);
                        String modeStr = w.optString("mode", winMode);
                        boolean trustFlag = w.optBoolean("trusted", winTrusted);
                        if (apply >= 0 && apply != winApplySeq) {
                            winApplySeq = apply;
                            winFormat = fmt;
                            winHw = hw;
                            winViewAlpha = va;
                            winLayer = layer;
                            winProbe = probeFlag;
                            winType = typeStr;
                            winFlagSet = flagStr;
                            winMode = modeStr;
                            winTrusted = trustFlag;
                            main.post(PetService.this::applyWinMode);
                        }
                    }
                    long seq = t.optLong("reloadSeq", 0);
                    if (!tuneSeen) {
                        tuneSeen = true;
                        tuneReloadSeq = seq;
                    } else if (seq != tuneReloadSeq) {
                        tuneReloadSeq = seq;
                        log("tune: reload requested");
                        main.post(this::loadPetPage);
                    }
                    synchronized (lock) {
                        if (!pending) {
                            pending = true;
                            main.post(PetService.this::flushBounds);
                        }
                    }
                }
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ---------------- 自检 ----------------

    private void loopStatus() {
        while (running) {
            try {
                final CountDownLatch latch = new CountDownLatch(1);
                final String[] out = new String[1];
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (web != null) {
                                web.evaluateJavascript("window.__petHost?window.__petHost.info():''", v -> {
                                    out[0] = v;
                                    latch.countDown();
                                });
                            } else {
                                latch.countDown();
                            }
                        } catch (Throwable t) {
                            latch.countDown();
                        }
                    }
                });
                latch.await(2, TimeUnit.SECONDS);
                if (out[0] != null && out[0].length() > 2) lastJsInfo = out[0];
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private String dump() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", web != null);
            o.put("running", running);
            o.put("density", density);
            o.put("screen", screenW + "x" + screenH);
            o.put("areaTop", areaTop);
            o.put("areaH", areaH);
            o.put("areaW", areaW);
            o.put("pingN", pingN);
            o.put("pingAgoMs", pingOkAt == 0 ? -1 : (System.currentTimeMillis() - pingOkAt));
            o.put("rotated", areaW > areaH ? "landscape" : "portrait");
            o.put("pageLoads", pageLoads);
            o.put("shimFrom", shimFrom);
            o.put("extraFrom", extraFrom);
            o.put("tuneFileSeen", tuneFileSeen);
            o.put("appVersion", APP_VER);
            o.put("winMode", winMode + (a11yOn ? "(a11y-on)" : "(a11y-off)"));
            o.put("a11yReady", PetA11y.isReady());
            o.put("winTrusted", winTrusted);
            o.put("winCfg", winMode + " " + winFormat + "/" + winFlagSet + "/" + winType + "/" + winLayer
                    + "/va=" + winViewAlpha + "/wa=" + winWinAlpha + "/hw=" + winHw);
            o.put("lastError", lastError);
            JSONObject win = new JSONObject();
            win.put("x", lpRender == null ? -1 : lpRender.x);
            win.put("y", lpRender == null ? -1 : lpRender.y);
            win.put("w", lpRender == null ? -1 : lpRender.width);
            win.put("h", lpRender == null ? -1 : lpRender.height);
            o.put("render", win);
            JSONObject tw = new JSONObject();
            tw.put("x", lpTouch == null ? -1 : lpTouch.x);
            tw.put("y", lpTouch == null ? -1 : lpTouch.y);
            tw.put("w", lpTouch == null ? -1 : lpTouch.width);
            tw.put("h", lpTouch == null ? -1 : lpTouch.height);
            o.put("touch", tw);
            JSONObject b = new JSONObject();
            b.put("boxX", boxX);
            b.put("boxY", boxY);
            b.put("size", petSize);
            b.put("marginT", marginT);
            b.put("marginR", marginR);
            o.put("box", b);
            o.put("inputRegions", inputRegions == null ? "" : inputRegions);
            JSONObject v = new JSONObject();
            v.put("webAlpha", web == null ? -1 : web.getAlpha());
            v.put("webVisibility", web == null ? -1 : web.getVisibility());
            v.put("webWinVisibility", web == null ? -1 : web.getWindowVisibility());
            v.put("webShown", web != null && web.isShown());
            v.put("webAttached", web != null && web.isAttachedToWindow());
            v.put("lpAlpha", lpRender == null ? -1 : lpRender.alpha);
            v.put("lpFormat", lpRender == null ? -1 : lpRender.format);
            v.put("lpFlags", lpRender == null ? -1 : lpRender.flags);
            try {
                android.view.ViewParent par = web == null ? null : web.getParent();
                v.put("parentClass", par == null ? "null" : par.getClass().getSimpleName());
                v.put("parentAlpha", par instanceof View ? ((View) par).getAlpha() : -1);
                if (par instanceof View) {
                    android.view.ViewParent gp = ((View) par).getParent();
                    v.put("grandClass", gp == null ? "null" : gp.getClass().getSimpleName());
                    v.put("grandAlpha", gp instanceof View ? ((View) gp).getAlpha() : -1);
                }
            } catch (Throwable ignore) {
            }
            o.put("view", v);
            JSONObject pr = new JSONObject();
            pr.put("on", probeView != null && lpProbe != null);
            pr.put("x", lpProbe == null ? -1 : lpProbe.x);
            pr.put("y", lpProbe == null ? -1 : lpProbe.y);
            pr.put("w", lpProbe == null ? -1 : lpProbe.width);
            pr.put("h", lpProbe == null ? -1 : lpProbe.height);
            pr.put("type", winType);
            o.put("probe", pr);
            o.put("flags", winFlagSet);
            o.put("page", lastJsInfo);
            JSONArray ls = new JSONArray();
            synchronized (logs) {
                for (String s : logs) ls.put(s);
            }
            o.put("logs", ls);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }

    private String evalJs(String code) {
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] out = new String[1];
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (web == null) {
                            out[0] = "no-web";
                            latch.countDown();
                            return;
                        }
                        web.evaluateJavascript(code, v -> {
                            out[0] = v;
                            latch.countDown();
                        });
                    } catch (Throwable t) {
                        out[0] = "err:" + t;
                        latch.countDown();
                    }
                }
            });
            latch.await(4, TimeUnit.SECONDS);
            return out[0] == null ? "" : out[0];
        } catch (Throwable t) {
            return "err:" + t;
        }
    }

    /** 本机调试端口：只绑 127.0.0.1，容器里就能问 App 内部状态。 */
    private class DebugServer implements Runnable {
        @Override
        public void run() {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 17999), 8);
                log("debug server on 17999");
                while (running) {
                    Socket sk = ss.accept();
                    try {
                        handle(sk);
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable t) {
                log("debug server died " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                try {
                    if (ss != null) ss.close();
                } catch (Throwable ignore) {
                }
            }
        }

        private void handle(Socket sk) throws Exception {
            try {
                sk.setSoTimeout(6000);
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new BufferedInputStream(sk.getInputStream())));
                String line = r.readLine();
                if (line == null) return;
                String path = line.split(" ")[1];
                String body;
                if (path.startsWith("/eval")) {
                    int i = path.indexOf("js=");
                    String code = i >= 0 ? URLDecoder.decode(path.substring(i + 3), "UTF-8") : "1";
                    body = "{\"ok\":true,\"result\":" + JSONObject.quote(evalJs(code)) + "}";
                } else {
                    body = dump();
                }
                byte[] out = body.getBytes("UTF-8");
                OutputStream os = sk.getOutputStream();
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                os.write(out);
                os.flush();
            } finally {
                try {
                    sk.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (web != null) {
                wm.removeView(web);
                web.destroy();
                web = null;
            }
        } catch (Throwable ignore) {
        }
        try {
            if (touch != null) wm.removeView(touch);
        } catch (Throwable ignore) {
        }
        log("service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}
