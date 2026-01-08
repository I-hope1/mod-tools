package modtools.android;

import android.content.Context;
import android.text.*;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.inputmethod.*;
import android.widget.*;
import arc.Core;
import arc.backend.android.*;
import arc.input.KeyCode;
import arc.scene.event.*;
import arc.scene.ui.TextField;
import arc.util.Log;

public class WSAInputFixer {
    private static EditText proxyView;
    private static boolean isInternalChange = false;
    private static InputListener focusListener; // 持有监听器引用以便卸载

    public static void install() {
        if (!(Core.app instanceof AndroidApplication app)) return;

        // 如果已经安装，先卸载旧的（防止重复安装）
        if (proxyView != null) {
            uninstall();
        }

        app.runOnUiThread(() -> {
            try {
                proxyView = new EditText(app);
                proxyView.setLayoutParams(new LayoutParams(1, 1));
                proxyView.setAlpha(0);
                proxyView.setBackground(null);

                proxyView.setOnKeyListener((v, keyCode, event) -> {
                    if (Core.input instanceof AndroidInput input) {
                        return input.onKey(null, keyCode, event);
                    }
                    return false;
                });

                proxyView.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void afterTextChanged(Editable s) {
                        if (BaseInputConnection.getComposingSpanStart(s) == -1 && s.length() > 0) {
                            isInternalChange = true;
                            s.clear();
                            isInternalChange = false;
                        }
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (isInternalChange || count <= 0) return;
                        Editable editable = proxyView.getText();
                        if (BaseInputConnection.getComposingSpanStart(editable) == -1 &&
                            BaseInputConnection.getComposingSpanEnd(editable) == -1) {
                            String committed = s.subSequence(start, start + count).toString();
                            injectToArc(committed);
                        }
                    }
                });

                // 添加到安卓根布局
                FrameLayout root = app.getWindow().getDecorView().findViewById(android.R.id.content);
                root.addView(proxyView);

                // 设置并添加焦点同步监听
                setupFocusSync();

                Log.info("WSA IME Proxy installed.");
            } catch (Exception e) {
                Log.err("WSA Fix Install Error", e);
            }
        });
    }

    /**
     * 执行热卸载，彻底清理资源
     */
    public static void uninstall() {
        if (!(Core.app instanceof AndroidApplication app)) return;

        // 移除场景监听
        if (focusListener != null) {
            Core.scene.removeCaptureListener(focusListener);
            focusListener = null;
        }

        // UI 线程清理组件
        app.runOnUiThread(() -> {
            try {
                if (proxyView != null) {
                    // 隐藏键盘
                    InputMethodManager imm = (InputMethodManager) proxyView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(proxyView.getWindowToken(), 0);

                    // 从父布局移除
                    ViewGroup parent = (ViewGroup) proxyView.getParent();
                    if (parent != null) {
                        parent.removeView(proxyView);
                    }
                    proxyView = null;
                }
                Log.info("WSA IME Proxy uninstalled.");
            } catch (Exception e) {
                Log.err("WSA Fix Uninstall Error", e);
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
        imm.showSoftInput(proxyView, InputMethodManager.SHOW_IMPLICIT);
    }

    private static void injectToArc(String text) {
        if (!(Core.input instanceof AndroidInput input)) return;
        try {
            synchronized (input) {
                for (char c : text.toCharArray()) {
                    AndroidInputFix.injectEventGlobal(System.nanoTime(), AndroidInputFix.KEY_TYPED, KeyCode.unknown, c);
                }
            }
            Core.graphics.requestRendering();
        } catch (Exception e) {
            Log.err("Injection failed", e);
        }
    }
}