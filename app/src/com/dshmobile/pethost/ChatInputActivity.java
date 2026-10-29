package com.dshmobile.pethost;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * 隐形输入代理（v5，2026-10-29）—— 对话弹窗改回「原插件那一套」之后，它只干一件事：托住系统输入法。
 *
 * 背景：用户要求对话界面和原插件（dsh-pet 桌面端）一致。界面已经交给页面里的
 * window.PetShared.mountChatDialog（同一个组件、同一份 CSS），但悬浮窗是 FLAG_NOT_FOCUSABLE，
 * 里面的 WebView 永远拿不到 IME（改可聚焦会触发 ColorOS「可能造成部分操作无响应」）。
 * 所以留这个 Activity 当**看不见的键盘宿主**：
 *
 *   · 窗口只有 2dp 高、贴屏幕底部，全透明（不盖屏幕 → 不会触发 ROM 的"遮挡"提示）；
 *   · 里面一个透明 EditText，唯一作用是被系统当成输入目标，把输入法拉起来；
 *   · 每次文本变化（含输入法组词）→ PetService.setChatDraft() → 页面弹窗里同步显示；
 *   · 输入法高度 → PetService.setChatIme() → 页面把弹窗抬到键盘上方（她靠下时不被盖住）；
 *   · 回车/发送 → PetService.deliverChat() → 页面 __petHost.chatSend() → 上游弹窗自己的
 *     doSend() POST /chat（同一个接口、同一份 memory.json、同一套人设）；
 *   · 返回键：第一下收键盘（卡片留着，点它可再拉起），第二下才结束 → 页面把弹窗一起关掉。
 *
 * 曾经的 v4.0 是屏幕底部一条可见输入条（自带「发送/×」），和原插件长得不一样，所以换掉了。
 */
public class ChatInputActivity extends Activity {

    private EditText input;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 已经提交过（deliverChat 已发）——结束时别再通知页面"取消了"。 */
    private boolean submitted = false;
    /** 正在收尾（提交/返回）——避免 IME 收起回调再走一遍 finish。 */
    private boolean closing = false;
    private int lastIme = -1;
    private String lastDraft = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PetService.attachChatInput(this);

        Window w = getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        w.setDimAmount(0f);
        try {
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Throwable ignore) {
        }

        // ★ 窗口 = 屏幕底部 2dp 的一条**全透明**细线。
        //   为什么要小：v3.9 的全屏半透明 Activity 被 ColorOS 判成"遮挡"，会弹
        //   「可能造成部分操作无响应」；窗口只要在几何上没盖住屏幕，系统就没有可判的东西。
        //   为什么还要有窗口：输入法必须挂在某个**有焦点的普通窗口**里的可编辑控件上。
        WindowManager.LayoutParams wl = w.getAttributes();
        wl.gravity = Gravity.BOTTOM;
        wl.width = WindowManager.LayoutParams.MATCH_PARENT;
        wl.height = Math.max(1, dp(2));
        wl.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        w.setAttributes(wl);

        setContentView(buildUi());
        watchIme(w.getDecorView());
        PetService.logChat("ChatInputActivity onCreate (invisible proxy)");

        // 草稿：页面把卡片里已有的字带过来（上一次发送失败后重开键盘时用）
        String draft = null;
        try {
            draft = getIntent() == null ? null : getIntent().getStringExtra("draft");
        } catch (Throwable ignore) {
        }
        if (draft != null && draft.length() > 0) {
            input.setText(draft);
            input.setSelection(draft.length());
        }
        lastDraft = input.getText() == null ? "" : input.getText().toString();

        input.requestFocus();
        ui.postDelayed(this::showIme, 180);

        // 兜底：1.5s 还没收到"键盘已弹出"的 insets（个别 ROM 对极小窗口不派发输入法），
        // 就把窗口长到 48dp 再要一次。只在失败时发生，正常路径窗口一直是 2dp。
        ui.postDelayed(() -> {
            if (Build.VERSION.SDK_INT < 30 || closing || lastIme > 0) return;
            PetService.logChat("ChatInputActivity ime not shown in 1.5s -> enlarge window");
            try {
                WindowManager.LayoutParams p = getWindow().getAttributes();
                p.height = dp(48);
                getWindow().setAttributes(p);
            } catch (Throwable ignore) {
            }
            showImeAgain();
        }, 1500);

        boolean autotest = false;
        try {
            autotest = getIntent() != null && getIntent().getBooleanExtra("autotest", false);
        } catch (Throwable ignore) {
        }
        if (autotest) {
            PetService.logChat("ChatInputActivity 自检：0.9s 后自动发送");
            ui.postDelayed(() -> {
                String t = "自检：对话通道正常吗";
                PetService.logChat("ChatInputActivity 自检发送 len=" + t.length());
                submitted = true;
                closing = true;
                PetService.deliverChat(t);
                finish();
            }, 900);
        }
    }

    /**
     * 透明窗口里只有一个**不可见**的 EditText：窗口 2dp 高、控件填满窗口，
     * 背景/文字/光标全透明，除了"能接输入法"之外不产生任何视觉。
     */
    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        input = new EditText(this);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setTextColor(Color.TRANSPARENT);
        input.setHintTextColor(Color.TRANSPARENT);
        input.setTextSize(1f);
        input.setCursorVisible(false);
        input.setSingleLine(true);
        input.setPadding(0, 0, 0, 0);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String t = s == null ? "" : s.toString();
                if (t.equals(lastDraft)) return;
                lastDraft = t;
                PetService.setChatDraft(t);
            }
        });
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submit();
                return true;
            }
            return false;
        });

        root.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** 输入法高度上报：页面据此把弹窗抬到键盘上方。
     *  **不**在"键盘收起"时自动关闭对话：一是系统会在很多时机瞬时报 0（切后台/旋转），
     *  二是"先收键盘、再按返回才退出"本来就是聊天窗口的常规两步，突然把整张卡片也吞掉更吓人。 */
    private void watchIme(View contentRoot) {
        if (Build.VERSION.SDK_INT < 30) return;
        contentRoot.setOnApplyWindowInsetsListener((v, ins) -> {
            final int ime = ins.getInsets(WindowInsets.Type.ime()).bottom;
            if (ime != lastIme) {
                lastIme = ime;
                PetService.logChat("ChatInputActivity ime=" + ime);
                PetService.setChatIme(Math.max(0, ime));
            }
            return ins;
        });
    }

    /** 页面点卡片时调用：键盘已经收起了就再拉起来（Activity 还活着时走这条路，不再开第二个）。 */
    public void showImeAgain() {
        input.requestFocus();
        showIme();
    }

    private void showIme() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable t) {
            PetService.logChat("ChatInputActivity showIme failed " + t);
        }
    }

    private void submit() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        PetService.logChat("ChatInputActivity submit len=" + text.length());
        closing = true;
        PetService.setChatIme(0);
        if (!text.isEmpty()) {
            submitted = true;
            PetService.deliverChat(text);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        // 键盘开着时系统会先把键盘收掉，再按一次才走到这里 → 关对话。
        if (!closing) {
            closing = true;
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        closing = true;
        PetService.setChatIme(0);
        PetService.detachChatInput(this);
        if (!submitted) {
            // 取消/返回：让页面把弹窗一起关掉，两边状态一致
            PetService.chatInputClosed(this);
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
