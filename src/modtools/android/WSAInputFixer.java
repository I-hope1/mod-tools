package modtools.android;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import arc.Core;
import arc.backend.android.AndroidApplication;
import arc.backend.android.AndroidInput;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.TextField;
import arc.util.Log;

public class WSAInputFixer {
    private static EditText proxyView;
    private static InputListener focusListener;

    public static void install() {
        if (!(Core.app instanceof AndroidApplication app)) return;

        if (proxyView != null) uninstall();

        app.runOnUiThread(() -> {
            try {
                // 1. 创建一个特殊的 EditText，拦截输入连接
                proxyView = new EditText(app) {
                    @Override
                    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                        InputConnection ic = super.onCreateInputConnection(outAttrs);
                        if (ic == null) return null;

                        // 关键：设置为普通文本类型，不带自动补全，减少输入法干扰
                        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
                        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

                        // 返回我们的拦截器
                        return new ArcInputConnection(ic, true);
                    }
                };

                // 2. 设置 View 属性，使其不可见但可交互
                proxyView.setLayoutParams(new ViewGroup.LayoutParams(1, 1));
                proxyView.setAlpha(0);
                proxyView.setBackground(null);
                proxyView.setFocusable(true);
                proxyView.setFocusableInTouchMode(true);

                // 3. 处理实体键盘按键（以防万一）
                proxyView.setOnKeyListener((v, keyCode, event) -> {
                    if (Core.input instanceof AndroidInput input) {
                        return input.onKey(null, keyCode, event);
                    }
                    return false;
                });

                // 4. 添加到界面
                FrameLayout root = app.getWindow().getDecorView().findViewById(android.R.id.content);
                root.addView(proxyView);

                setupFocusSync();
                Log.info("WSA IME Fixer Installed (Clean Mode)");
            } catch (Exception e) {
                Log.err("WSA Fix Install Error", e);
            }
        });
    }

    public static void uninstall() {
        if (!(Core.app instanceof AndroidApplication app)) return;
        if (focusListener != null) {
            Core.scene.removeCaptureListener(focusListener);
            focusListener = null;
        }
        app.runOnUiThread(() -> {
            try {
                if (proxyView != null) {
                    InputMethodManager imm = (InputMethodManager) proxyView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(proxyView.getWindowToken(), 0);
                    ViewGroup parent = (ViewGroup) proxyView.getParent();
                    if (parent != null) parent.removeView(proxyView);
                    proxyView = null;
                }
            } catch (Exception e) {
                Log.err(e);
            }
        });
    }

    private static void setupFocusSync() {
        focusListener = new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (event.targetActor instanceof TextField) {
                    ((AndroidApplication) Core.app).runOnUiThread(WSAInputFixer::syncFocusToNative);
                }
                return false;
            }
        };
        Core.scene.addCaptureListener(focusListener);
    }

    private static void syncFocusToNative() {
        if (proxyView == null) return;
        proxyView.requestFocus();
        InputMethodManager imm = (InputMethodManager) proxyView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(proxyView, InputMethodManager.SHOW_IMPLICIT);
    }

    // 核心注入逻辑
    private static void injectToArc(String text) {
        if (!(Core.input instanceof AndroidInput input)) return;
        if (text == null || text.length() == 0) return;

        // Log.info("[WSA Fix] Injecting Final: " + text); // 仅调试用，正式发布可删除

        try {
            // 使用 synchronized 确保线程安全
            synchronized (input) {
                for (char c : text.toCharArray()) {
                    if (c == '\n') {
                        AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_DOWN, KeyCode.enter, '\n');
                        AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_UP, KeyCode.enter, '\n');
                        AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_TYPED, KeyCode.unknown, '\n');
                    } else {
                        AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_TYPED, KeyCode.unknown, c);
                    }
                }
            }
        } catch (Exception e) {
            Log.err("Injection Failed", e);
        }
    }

    /**
     * 自定义输入连接包装器
     * 负责区分 "拼音过程" 和 "最终结果"
     */
    private static class ArcInputConnection extends InputConnectionWrapper {
        public ArcInputConnection(InputConnection target, boolean mutable) {
            super(target, mutable);
        }

        // 关键点1：setComposingText 是拼音输入过程（如输入 "sa'j"）
        // 我们只调用 super 让原生 View 显示下划线文本，绝对不注入到游戏！
        // 同时返回 true 告诉输入法我们处理了，防止它回退到 commitText
        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return super.setComposingText(text, newCursorPosition);
        }

        // 关键点2：commitText 是用户选词结束（如选择了 "撒娇"）
        // 这里才执行注入，并且调用 super 维护原生 View 状态
        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (text != null && text.length() > 0) {
                injectToArc(text.toString());
            }
            // 不要在这里调用 clear()，这会导致 Span 错误。
            // 只要让 proxyView 里的字一直堆积即可，它是不可见的，不会有影响。
            return super.commitText(text, newCursorPosition);
        }

        // 关键点3：处理软键盘删除键
        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            // 如果是删除前一个字符，模拟退格键
            if (beforeLength == 1 && afterLength == 0) {
                AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_DOWN, KeyCode.backspace, (char)8);
                AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_UP, KeyCode.backspace, (char)8);
                AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_TYPED, KeyCode.unknown, (char)8);
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        // 关键点4：处理回车和其他特殊键
        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    injectToArc("\n");
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                    AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_DOWN, KeyCode.backspace, (char)8);
                    AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_UP, KeyCode.backspace, (char)8);
                    AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_TYPED, KeyCode.unknown, (char)8);
                    return true;
                }
            }
            return super.sendKeyEvent(event);
        }
    }
}