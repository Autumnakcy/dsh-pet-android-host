package com.dshmobile.pethost;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 控制面板（v2.6 重做视觉）。
 *
 * 只做三件事：看状态（权限 / 悬浮窗 / DSH 连接）、起停悬浮窗、给两条备用入口。
 * 全部用系统控件 + GradientDrawable 手绘，不引第三方库（离线环境装不了依赖）。
 */
public class MainActivity extends Activity {

    private static final int REQ_CAP = 1001;
    private static final int REQ_STORAGE = 1002;

    private static final int BG = 0xFF0B0D12;
    private static final int CARD = 0xFF161A22;
    private static final int TEXT = 0xFFEDF1F7;
    private static final int MUTED = 0xFF8A93A6;
    private static final int ACCENT = 0xFF5B8CFF;
    private static final int OK = 0xFF35C77A;
    private static final int WARN = 0xFFE8952F;
    private static final int BAD = 0xFFE5484D;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView chipPerm, chipWindow, chipLink;
    private android.widget.Switch bootSwitch;
    private TextView shotInfo;
    private TextView primary, secondary;
    private LinearLayout root;
    private volatile boolean linked = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(22), dp(34), dp(22), dp(28));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        // ---- 头部：她本人 + 标题 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this);
        int iconId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (iconId != 0) avatar.setImageResource(iconId);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(76), dp(76));
        alp.rightMargin = dp(16);
        head.addView(avatar, alp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("DSH 桌宠", 26, TEXT, true));
        TextView sub = text("蓝色大肥鱼 · 和 dsh 联动", 12, MUTED, false);
        sub.setPadding(0, dp(6), 0, 0);
        titles.addView(sub);
        head.addView(titles);
        root.addView(head);

        // ---- 状态卡 ----
        LinearLayout statusCard = card();
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chipPerm = chip("权限");
        chipWindow = chip("悬浮窗");
        chipLink = chip("DSH");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(8);
        chips.addView(chipPerm, clp);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp2.rightMargin = dp(8);
        chips.addView(chipWindow, clp2);
        chips.addView(chipLink, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusCard.addView(chips);
        root.addView(statusCard, marginTop(dp(22)));

        // ---- 主按钮 ----
        primary = bigButton("打开悬浮窗", ACCENT, 0xFF7FB0FF);
        primary.setOnClickListener(v -> startPet());
        root.addView(primary, marginTop(dp(18)));

        secondary = bigButton("关掉悬浮窗", 0xFF2A303D, 0xFF333A49);
        secondary.setOnClickListener(v -> {
            stopService(new Intent(this, PetService.class));
            ui.postDelayed(this::refresh, 400);
        });
        root.addView(secondary, marginTop(dp(10)));

        // ---- 权限入口 ----
        TextView perm = ghostButton("授权悬浮窗权限");
        perm.setOnClickListener(v -> askOverlay());
        root.addView(perm, marginTop(dp(10)));

        // ---- 自启动（v6.6）----
        // 两层：① 我们自己的「开机自动启动」开关（落盘，BootReceiver 读它）
        //       ② ColorOS 那层「自启动权限」——系统不给读状态，只能跳过去让用户开一次
        LinearLayout bootCard = card();
        LinearLayout bootRow = new LinearLayout(this);
        bootRow.setOrientation(LinearLayout.HORIZONTAL);
        bootRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView bootLabel = text("开机自动启动", 14, TEXT, false);
        bootRow.addView(bootLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bootSwitch = new android.widget.Switch(this);
        bootSwitch.setChecked(Boot.enabled(this));
        bootSwitch.setOnCheckedChangeListener((v, on) -> {
            Boot.set(this, on);
            toast(on ? "已打开：重启后会自动出现" : "已关闭：重启后不会自动出现");
        });
        bootRow.addView(bootSwitch);
        bootCard.addView(bootRow);


        TextView bootBtn = ghostButton("打开系统自启动权限");
        bootBtn.setOnClickListener(v -> openAutostart());
        LinearLayout.LayoutParams blp = marginTop(dp(10));
        bootCard.addView(bootBtn, blp);
        root.addView(bootCard, marginTop(dp(14)));

        // 截屏诊断/浏览器试跑/玩法说明已去掉：界面只留真正要用的
        shotInfo = text("", 11, 0xFF5A6273, false);


        new Thread(() -> ping("ui opened v7")).start();
    }

    // ---------------- 交互 ----------------

    private void startPet() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            askOverlay();
            return;
        }
        Intent i = new Intent(this, PetService.class);
        if (PetService.isRunning()) {
            // v6.9：正在跑 → 让**同一个服务实例**自己重建窗口。
            // 不再 stopService + startForegroundService 连发（两个请求抢同一个 ServiceRecord，
            // 旧窗没摘干净新窗就建起来 → 屏幕上多一只）。
            i.setAction(PetService.ACTION_RESTART);
            startService(i);
            toast("正在重启悬浮窗…");
            ui.postDelayed(this::refresh, 1200);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        ui.postDelayed(this::refresh, 1600);
    }

    private void askOverlay() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * 「自启动权限」这条捷径（v6.6）。
     *
     * 现实：自启动是各家 ROM 自己管的私有设置，**没有公开 API 能读、也没法写**
     * （ColorOS 上它在「手机管家 → 应用自启动管理」里）。所以这里做的是**把那个页面给你打开**，
     * 开关本身还是系统那个 —— 我们能做的只是让你少点几层。
     *
     * 查找顺序：本机可能是 ColorOS / realme UI / 别的 ROM，逐个组件试，
     * 谁能 resolve 就用谁；全都不认就退回「应用信息」页（那里通常也能找到自启动/耗电管理）。
     * 结果写一行字在卡片里，也顺手上报给插件（方便以后按机型补名单）。
     */
    private static final String[][] AUTOSTART_PAGES = {
            // ColorOS 12+ / OPPO、realme、一加（oplus 整合后）
            {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
            // ColorOS 7~11 / OPPO
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
            // 其它常见 ROM（顺手兜着，反正 resolve 不到就跳过）
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
    };

    private void openAutostart() {
        String[] hit = null;
        for (String[] p : AUTOSTART_PAGES) {
            try {
                Intent i = new Intent();
                i.setComponent(new android.content.ComponentName(p[0], p[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().resolveActivity(i,
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    startActivity(i);
                    hit = p;
                    break;
                }
            } catch (Throwable ignore) {
                // 下一个
            }
        }
        if (hit != null) {
            toast("已打开系统自启动管理页，把「DSH 桌宠悬浮窗」打开");
            final String tag = hit[0] + "/" + hit[1];
            new Thread(() -> ping("autostart page " + tag)).start();
            return;
        }
        // 兜底：应用信息页（ColorOS 上这里也能摸到自启动/耗电管理）
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("已跳到「应用信息」，在里面找自启动 / 耗电管理");
            new Thread(() -> ping("autostart fallback appinfo")).start();
        } catch (Throwable t) {
            toast("打不开系统设置，请手动去：手机管家 → 应用自启动管理");
            new Thread(() -> ping("autostart failed " + t)).start();
        }
    }

    /** 截屏诊断：先把真实屏幕抓一张到 /sdcard/dsh/pet-shot.png（只抓一帧，不常驻）。 */
    private void grabShot() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        shotInfo.setText("请在系统弹窗里点「立即开始」…");
        Shot.request(this, REQ_CAP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAP) {
            if (res != RESULT_OK || data == null) {
                shotInfo.setText("已取消截屏");
                return;
            }
            shotInfo.setText("抓帧中…");
            Shot.capture(this, res, data, (ok, detail) -> ui.post(() -> {
                shotInfo.setText(ok ? "已保存 " + detail : "失败：" + detail);
                new Thread(() -> ping("shot " + (ok ? "ok " : "fail ") + detail)).start();
            }));
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_STORAGE) grabShot();
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignore) {
        }
    }

    /** 告诉插件 App 被打开过（顺便当作连通性探针）。 */
    private void ping(String m) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:3080/dsh-pet-android/report").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.getOutputStream().write(("{\"upstreamOk\":false,\"error\":\"" + m + "\"}").getBytes("UTF-8"));
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignore) {
        }
    }

    private void checkLink() {
        new Thread(() -> {
            boolean ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://127.0.0.1:3080/dsh-pet-android/status").openConnection();
                c.setConnectTimeout(1200);
                c.setReadTimeout(1200);
                ok = c.getResponseCode() == 200;
                c.disconnect();
            } catch (Throwable ignore) {
            }
            final boolean res = ok;
            ui.post(() -> {
                linked = res;
                refresh();
            });
        }, "link-check").start();
    }

    // ---------------- 状态刷新 ----------------

    private void refresh() {
        boolean perm = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean run = PetService.isRunning();
        paint(chipPerm, perm ? "权限 已给" : "权限 未给", perm ? OK : BAD);
        paint(chipWindow, run ? "悬浮窗 开" : "悬浮窗 关", run ? OK : WARN);
        paint(chipLink, linked ? "DSH 在线" : "DSH 离线", linked ? OK : BAD);
        primary.setText(run ? "重启悬浮窗" : "打开悬浮窗");
        secondary.setAlpha(run ? 1f : 0.45f);
        secondary.setEnabled(run);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        checkLink();
    }


    // ---------------- 视觉小工具 ----------------

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(round(CARD, 18, 0));
        return c;
    }

    private LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px;
        return lp;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private GradientDrawable grad(int from, int to, int radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{from, to});
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView chip(String label) {
        TextView t = text(label + " —", 12, MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(9), dp(8), dp(9));
        t.setBackground(round(0xFF1F2430, 12, 0));
        return t;
    }

    private void paint(TextView t, String label, int color) {
        t.setText(label);
        t.setTextColor(color);
    }

    private TextView bigButton(String label, int from, int to) {
        TextView b = text(label, 16, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(16), 0, dp(16));
        b.setBackground(grad(from, to, 16));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView ghostButton(String label) {
        TextView b = text(label, 14, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(13), 0, dp(13));
        b.setBackground(round(0xFF1B2029, 14, 0xFF2A303D));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** 让屏幕底部导航栏跟深色背景融在一起。 */
    private void dressWindow() {
        try {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        } catch (Throwable ignore) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().getInsetsController().setSystemBarsAppearance(0, 0);
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        dressWindow();
    }
}
