package com.dshmobile.pethost;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 控制面板（v6.11：无障碍权限独立成按钮 + 面板可换自定义背景图）。
 *
 * 只做四件事：看状态（权限 / 悬浮窗 / DSH 连接）、起停悬浮窗、授权两种权限、换面板背景。
 * 全部用系统控件 + GradientDrawable 手绘，不引第三方库（离线环境装不了依赖）。
 *
 * v6.11 两处改动（铭秋哥哥 10-29 要的）：
 *   ① 无障碍权限从「服务里偷偷跳一次设置页」改成面板上**独立的一个按钮**，
 *      并且实时显示开没开（读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES，不需要权限）。
 *   ② 整个面板支持**用户自定义背景图**：从相册选一张 → 拷进 App 私有目录 → 下次打开还在。
 *      图片上盖一层压暗（scrim），卡片改成半透明，保证文字依然看得清。
 */
public class MainActivity extends Activity {

    private static final int REQ_CAP = 1001;
    private static final int REQ_STORAGE = 1002;
    private static final int REQ_PICK_BG = 1003;

    private static final int BG = 0xFF0B0D12;
    private static final int CARD = 0xFF161A22;
    /** 有背景图时的卡片色：半透明，让图片从卡片下面透一点出来 */
    private static final int CARD_IMG = 0xE0161A22;
    /** 盖在背景图上的压暗层（60% 黑），不然浅色图片上白字看不清 */
    private static final int SCRIM = 0x99000000;
    private static final int TEXT = 0xFFEDF1F7;
    private static final int MUTED = 0xFF8A93A6;
    private static final int ACCENT = 0xFF5B8CFF;
    private static final int A11Y_ACCENT = 0xFF9E8BFF;
    private static final int OK = 0xFF35C77A;
    private static final int WARN = 0xFFE8952F;
    private static final int BAD = 0xFFE5484D;

    /** 背景图存 App 私有目录：拷进来就不怕相册那边撤销授权 / 图片被移走 */
    private static final String BG_FILE = "panel-bg.img";
    private static final long BG_MAX_BYTES = 60L * 1024 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView chipPerm, chipWindow, chipLink;
    private TextView a11yState, a11yBtn;
    private TextView bgState, bgClearBtn;
    private android.widget.Switch bootSwitch;
    private TextView shotInfo;
    private TextView primary, secondary;
    private LinearLayout root;
    private FrameLayout shell;
    private int cardColor = CARD;
    private boolean hasBg = false;
    private volatile boolean linked = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        } catch (Throwable ignore) {
        }
        buildUi();
        new Thread(() -> ping("ui opened v8")).start();
    }

    // ---------------- 界面 ----------------

    /**
     * 整个界面在这里一次搭完。
     * 换背景时直接再调一次（旧视图会被 setContentView 整体换掉），
     * 比 recreate() 干净：不会闪一下、也不会重复跑 ping。
     */
    private void buildUi() {
        Bitmap bgBm = null;
        File bgf = bgFile();
        if (bgf.exists() && bgf.length() > 0) bgBm = loadBgBitmap();
        hasBg = bgBm != null;
        cardColor = hasBg ? CARD_IMG : CARD;

        shell = new FrameLayout(this);
        shell.setBackgroundColor(BG);

        if (hasBg) {
            ImageView bgView = new ImageView(this);
            bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bgView.setImageBitmap(bgBm);
            shell.addView(bgView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(this);
            scrim.setBackgroundColor(SCRIM);
            shell.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(dp(22), dp(34), dp(22), dp(28));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.TRANSPARENT);
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(shell);

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

        // ---- 权限：两个独立按钮（v6.11）----
        // 以前无障碍只在服务里"没开就偷偷跳一次设置页"，用户根本不知道该点哪儿。
        // 现在它自己占一排按钮 + 一行状态，开了没开一眼看见。
        LinearLayout permCard = card();
        permCard.addView(text("权限", 13, MUTED, true));

        a11yBtn = ghostButton("授权无障碍权限");
        a11yBtn.setTextColor(A11Y_ACCENT);
        a11yBtn.setOnClickListener(v -> askA11y());
        permCard.addView(a11yBtn, marginTop(dp(10)));

        a11yState = text("", 11, MUTED, false);
        a11yState.setPadding(dp(2), dp(8), dp(2), 0);
        permCard.addView(a11yState);

        TextView a11yHint = text("开了它，窗口才能整块全屏铺开、又不被系统压暗（模式 A）。" +
                "系统设置 → 已下载的服务 → 「DSH 桌宠悬浮窗」打开即可，只是借窗口权限，不读屏幕。",
                11, 0xFF5A6273, false);
        a11yHint.setPadding(dp(2), dp(4), dp(2), 0);
        permCard.addView(a11yHint);

        TextView overlayBtn = ghostButton("授权悬浮窗权限");
        overlayBtn.setOnClickListener(v -> askOverlay());
        permCard.addView(overlayBtn, marginTop(dp(14)));
        root.addView(permCard, marginTop(dp(14)));

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
        bootCard.addView(bootBtn, marginTop(dp(10)));
        root.addView(bootCard, marginTop(dp(14)));

        // ---- 面板背景图（v6.11）----
        LinearLayout bgCard = card();
        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        bgRow.setGravity(Gravity.CENTER_VERTICAL);
        bgRow.addView(text("面板背景", 14, TEXT, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bgState = text(hasBg ? "自定义" : "默认深色", 12, hasBg ? A11Y_ACCENT : MUTED, false);
        bgRow.addView(bgState);
        bgCard.addView(bgRow);

        TextView bgPick = ghostButton("从相册选一张图片");
        bgPick.setOnClickListener(v -> pickBackground());
        bgCard.addView(bgPick, marginTop(dp(10)));

        bgClearBtn = ghostButton("恢复默认背景");
        bgClearBtn.setAlpha(hasBg ? 1f : 0.45f);
        bgClearBtn.setEnabled(hasBg);
        bgClearBtn.setOnClickListener(v -> clearBackground());
        bgCard.addView(bgClearBtn, marginTop(dp(8)));

        TextView bgHint = text("图片会铺满整个面板，并自动压暗一层保证文字看得清；" +
                "只存在本机 App 里，不上传。", 11, 0xFF5A6273, false);
        bgHint.setPadding(dp(2), dp(8), dp(2), 0);
        bgCard.addView(bgHint);
        root.addView(bgCard, marginTop(dp(14)));

        // 截屏诊断/浏览器试跑/玩法说明已去掉：界面只留真正要用的
        shotInfo = text("", 11, 0xFF5A6273, false);
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
     * 无障碍权限按钮（v6.11）。
     *
     * 现实：无障碍服务**只能用户自己在系统设置里打开**，App 没有 API 能替用户开
     * （这是 Android 的安全边界，绕不过去）。所以这个按钮做的是把那一页打开，
     * 并在面板上实时显示到底开没开——不用再猜。
     */
    private void askA11y() {
        if (a11yEnabled()) {
            toast("已开启。要关掉的话，在这里把「DSH 桌宠悬浮窗」的开关关掉");
        } else {
            toast("在「已下载的服务」里找到「DSH 桌宠悬浮窗」，打开开关");
        }
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            new Thread(() -> ping("open a11y settings")).start();
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignore) {
                toast("打不开系统设置，请手动去：设置 → 无障碍");
            }
        }
    }

    /** 无障碍服务到底开没开：先看本进程里服务活没活，再看系统那份已启用列表。 */
    private boolean a11yEnabled() {
        if (PetA11y.isReady()) return true;
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null || s.length() == 0) return false;
            String want = getPackageName() + "/" + PetA11y.class.getName();
            for (String p : s.split(":")) {
                if (p.equalsIgnoreCase(want)) return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    // ---------------- 背景图 ----------------

    private File bgFile() {
        return new File(getFilesDir(), BG_FILE);
    }

    /**
     * 解码背景图。按屏幕宽度做 2 的幂降采样，RGB_565 省内存——
     * 背景图只需要"铺满"，不需要原图精度，几千万像素的相机照直接塞进 ImageView 会 OOM。
     */
    private Bitmap loadBgBitmap() {
        File f = bgFile();
        if (!f.exists() || f.length() == 0) return null;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            int w = probe.outWidth;
            if (w <= 0) return null;
            int limit = Math.max(1080, getResources().getDisplayMetrics().widthPixels);
            int sample = 1;
            while (w / sample > limit * 2 && sample < 16) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
        } catch (Throwable t) {
            return null;
        }
    }

    private void pickBackground() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK_BG);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_PICK_BG);
            } catch (Throwable t2) {
                toast("没找到能选图片的应用");
            }
        }
    }

    /**
     * 选完图：拷进 App 私有目录（不长期持有 content:// 授权，相册删了/撤销了也不怕），
     * 解码验证能打开才替换，然后重建界面。
     */
    private void importBackground(final Uri uri) {
        toast("正在读取这张图…");
        new Thread(() -> {
            boolean ok = false;
            File tmp = new File(getFilesDir(), BG_FILE + ".tmp");
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    OutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[16 * 1024];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > BG_MAX_BYTES) break;
                        out.write(buf, 0, n);
                    }
                    out.flush();
                    out.close();
                    in.close();
                    if (total > 0 && total <= BG_MAX_BYTES) {
                        File dst = bgFile();
                        if (dst.exists() && !dst.delete()) {
                            // 删不掉就用新文件覆盖，交给下一步验证
                        }
                        ok = tmp.renameTo(dst);
                        if (!ok) {
                            // 某些文件系统上跨目录 rename 会失败，退一步整体复制
                            ok = copyFile(tmp, dst);
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            if (tmp.exists()) tmp.delete();
            if (ok) {
                // 能解码才算成功，坏图直接丢掉，别让面板变成空白
                ok = loadBgBitmap() != null;
                if (!ok) {
                    File dst = bgFile();
                    if (dst.exists()) dst.delete();
                }
            }
            final boolean res = ok;
            ui.post(() -> {
                if (res) {
                    buildUi();
                    refresh();
                    toast("背景换好啦");
                    new Thread(() -> ping("bg set")).start();
                } else {
                    toast("这张图没读进来，换一张试试");
                }
            });
        }, "bg-import").start();
    }

    private boolean copyFile(File src, File dst) {
        try {
            InputStream in = new java.io.FileInputStream(src);
            OutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            return dst.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void clearBackground() {
        File f = bgFile();
        if (f.exists() && f.delete()) {
            buildUi();
            refresh();
            toast("已恢复默认深色背景");
        } else {
            toast("本来就没有自定义背景");
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
        if (req == REQ_PICK_BG) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            importBackground(data.getData());
            return;
        }
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
        boolean a11y = a11yEnabled();
        paint(chipPerm, perm ? "权限 已给" : "权限 未给", perm ? OK : BAD);
        paint(chipWindow, run ? "悬浮窗 开" : "悬浮窗 关", run ? OK : WARN);
        paint(chipLink, linked ? "DSH 在线" : "DSH 离线", linked ? OK : BAD);
        if (a11yState != null) {
            paint(a11yState, a11y ? "状态：已开启 ✓" : "状态：未开启", a11y ? OK : WARN);
        }
        if (a11yBtn != null) {
            a11yBtn.setText(a11y ? "无障碍已开启 · 去系统设置看看" : "授权无障碍权限");
        }
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
        c.setBackground(round(cardColor, 18, 0));
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
