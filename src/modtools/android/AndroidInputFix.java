package modtools.android;

import android.view.*;
import arc.backend.android.AndroidInput;
import arc.input.KeyCode;
import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.pooling.Pool;
import modtools.Constants.AndroidInput_KeyEvent;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.reflect.FieldUtils;

import java.util.ArrayList;

import static modtools.annotations.asm.Sample.SampleTemp._super;

@Sample
public class AndroidInputFix {
	static ObjectMap<AndroidInput, ObjectMap<String, Object>> allvariables = new ObjectMap<>();

	// 对应 AndroidInput 内部类 KeyEvent 的常量
	private static final int KEY_DOWN  = 0;
	private static final int KEY_UP    = 1;
	private static final int KEY_TYPED = 2;

	@SampleForMethod
	public static boolean onKey(AndroidInput self, View v, int keyCode, KeyEvent e) {
		ObjectMap<String, Object> variable = allvariables.get(self, ObjectMap::new);

		// 获取引擎内部队列
		ArrayList<Object> keyEvents = (ArrayList<Object>) variable.get("keyEvents", () ->
		 FieldUtils.getOrNull(FieldUtils.getFieldAccess(AndroidInput.class, "keyEvents"), self));
		Pool<Object> usedKeyEvents = (Pool<Object>) variable.get("usedKeyEvents", () ->
		 FieldUtils.getOrNull(FieldUtils.getFieldAccess(AndroidInput.class, "usedKeyEvents"), self));

		// 判定目标按键 (扩展 Home, End, PageUp/Down, Insert, ForwardDel)
		KeyCode targetCode = switch (keyCode) {
			case 113 -> KeyCode.controlLeft;   // CTRL_LEFT
			case 114 -> KeyCode.controlRight;  // CTRL_RIGHT
			case 67 -> KeyCode.backspace;     // Backspace
			case 112 -> KeyCode.forwardDel;    // Forward Delete (PC Delete)
			case 122 -> KeyCode.home;          // Home
			case 123 -> KeyCode.end;           // End
			case 124 -> KeyCode.insert;        // Insert
			case 92 -> KeyCode.pageUp;        // PageUp
			case 93 -> KeyCode.pageDown;      // PageDown
			default -> null;
		};

		if (targetCode != null && keyEvents != null && usedKeyEvents != null) {
			int  action = e.getAction();
			long time   = System.nanoTime();

			// [新增] 必须调用 startTracking，否则多键同按时(如Ctrl+C)容易丢失事件
			if (action == KeyEvent.ACTION_DOWN) {
				e.startTracking();
			}

			synchronized (self) {
				if (action == KeyEvent.ACTION_DOWN) {
					// 1. 注入 KEY_DOWN (允许重复，Arc 会处理为 pressed=true)
					injectEvent(usedKeyEvents, keyEvents, time, KEY_DOWN, targetCode, (char) 0);

					// 2. Backspace 特殊处理：按下时注入 TYPED 以支持长按连续删除
					if (targetCode == KeyCode.backspace) {
						injectEvent(usedKeyEvents, keyEvents, time, KEY_TYPED, KeyCode.unknown, '\b');
					}
					if (targetCode == KeyCode.forwardDel) {
						injectEvent(usedKeyEvents, keyEvents, time, KEY_TYPED, KeyCode.unknown, (char) 127);
					}
				} else if (action == KeyEvent.ACTION_UP) {
					// 3. 注入 KEY_UP
					injectEvent(usedKeyEvents, keyEvents, time, KEY_UP, targetCode, (char) 0);
				}
			}
			return true; // 拦截事件，不再交给 super 处理
		}

		return _super(self).onKey(v, keyCode, e);
	}
	private static void injectEvent(Pool<Object> pool, ArrayList<Object> list, long time, int type, KeyCode code,
	                                char ch) {
		try {
			Object event = pool.obtain();
			// 使用你的 Reflect 工具库
			UNSAFE.putLong(event, AndroidInput_KeyEvent.TIMESTAMP, time);
			UNSAFE.putInt(event, AndroidInput_KeyEvent.TYPE, type);
			UNSAFE.putObject(event, AndroidInput_KeyEvent.KEY_CODE, code);
			UNSAFE.putChar(event, AndroidInput_KeyEvent.KEY_CHAR, ch);
			list.add(event);
		} catch (Exception ex) {
			Log.err("InputFix inject error", ex);
		}
	}
}