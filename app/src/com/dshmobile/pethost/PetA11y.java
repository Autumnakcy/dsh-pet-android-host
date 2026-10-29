package com.dshmobile.pethost;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍悬浮层入口（v5.0 新增，思路来自官方 2.8 的 PetA11y）。
 *
 * 为什么需要它：这台 ROM（ColorOS / Android 16）会把 **不可触摸** 的
 * TYPE_APPLICATION_OVERLAY 窗口按 ≈0.79 合成。无障碍层
 * TYPE_ACCESSIBILITY_OVERLAY 走另一条合成规则，**穿透也不打折** ——
 * 这是唯一「又实心、又点击穿透」的合法窗口类型。
 *
 * 有了它，渲染窗就可以是「全屏 + 穿透 + 常驻不动」，触摸全部交给独立的触摸窗：
 * 不再需要「窗口跟着她跑」那套 margin 补偿，也就没有撑窗/收窗的回弹。
 *
 * 边界：不监听任何事件，只借用窗口权限；无障碍被关掉时回落模式 B（窗口跟着她跑）。
 */
public class PetA11y extends AccessibilityService {
    private static volatile PetA11y SELF;

    public static boolean isReady() {
        return SELF != null;
    }

    /** 无障碍窗口必须用无障碍服务自己的 WindowManager 来加，否则会抛权限异常 */
    public static WindowManager wm() {
        PetA11y s = SELF;
        if (s == null) return null;
        return (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        SELF = this;
        // 无障碍一开，顺手把悬浮窗服务拉起来（否则用户还得回 App 点一次"启动"）
        try {
            Intent i = new Intent(this, PetService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable ignored) {}
        try { PetService.onA11yChanged(); } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        if (SELF == this) SELF = null;
        super.onDestroy();
        // 无障碍被关掉：立刻重建窗口，回落模式 B，不能留一个没有触摸的透明全屏层
        try { PetService.onA11yChanged(); } catch (Throwable ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不监听事件，只借窗口权限
    }

    @Override
    public void onInterrupt() {
    }
}
