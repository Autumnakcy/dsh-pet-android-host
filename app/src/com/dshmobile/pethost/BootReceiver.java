package com.dshmobile.pethost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机广播 → 把她拉起来（v6.6）。
 *
 * 为什么需要它：PetService 是前台服务，手机重启之后系统不会替我们留住它 ——
 * 用户看到的就是「重启完桌宠不见了」。这个接收器在 BOOT_COMPLETED 时启动服务。
 *
 * 能不能真的起来，还看两件事：
 *   1. 我们自己的开关（Boot.enabled，控制面板里那个 Switch）；
 *   2. **ColorOS 的「自启动」权限** —— 这个系统层开关不给的话，广播根本送不到我们手里。
 *      那是系统管的、没有公开 API 读状态，所以控制面板里给了一个直接跳过去的按钮。
 *
 * Android 12+ 对「后台启动前台服务」有限制，但 BOOT_COMPLETED 属于官方豁免场景；
 * 万一还是被拦，异常会被吞掉，不影响别的流程。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context ctx, Intent i) {
        String a = i == null || i.getAction() == null ? "" : i.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a) || QUICKBOOT.equals(a);
        if (!boot) return;
        if (!Boot.enabled(ctx)) return;
        try {
            Intent s = new Intent(ctx, PetService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s);
            else ctx.startService(s);
        } catch (Throwable ignore) {
            // 系统拦了（自启动没给 / 后台启动前台服务被拒）：安静放弃，等用户自己点开
        }
    }
}
