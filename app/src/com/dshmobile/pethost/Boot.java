package com.dshmobile.pethost;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「开机自动启动」这个开关的落盘（v6.6）。
 *
 * 为什么要单独一个类：开关在 Activity 里点、真正干活的是 {@link BootReceiver}，
 * 两边读同一份 SharedPreferences，别各写各的。
 *
 * 注意：这里只管「我们自己在开机广播里拉不拉起她」；
 * ColorOS 那层的「自启动权限」是系统管的，读不到状态，只能跳过去让用户开（见 MainActivity）。
 */
public final class Boot {
    private static final String PREF = "pet-boot";
    private static final String KEY = "bootStart";

    private Boot() {
    }

    public static boolean enabled(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void set(Context c, boolean on) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            p.edit().putBoolean(KEY, on).apply();
        } catch (Throwable ignore) {
        }
    }
}
