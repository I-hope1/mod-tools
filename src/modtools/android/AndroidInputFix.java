package modtools.android;

import android.view.*;
import arc.*;
import arc.backend.android.AndroidInput;
import arc.input.KeyCode;
import arc.util.Log;
import arc.util.pooling.Pool;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import modtools.Constants.AndroidInput_KeyEvent;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;
import modtools.events.*;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.struct.LazyValue;
import modtools.utils.reflect.*;

import java.util.*;

import static modtools.annotations.asm.Sample.SampleTemp._super;

@Sample
public class AndroidInputFix {
	// 使用弱引用或在合适时机清理，防止内存泄漏。但在 Arc 环境下 AndroidInput 通常是单例，暂且保留
	static IdentityHashMap<AndroidInput, IdentityHashMap<String, Object>> allvariables = new IdentityHashMap<>();

	static final int KEY_DOWN  = 0;
	static final int KEY_UP    = 1;
	static final int KEY_TYPED = 2;

	@SampleForMethod
	public static boolean onKey(AndroidInput self, View v, int keyCode, KeyEvent e) {
		ArrayList<Object> keyEvents     = getKeyEvents(self);
		Pool<Object>      usedKeyEvents = getUsedKeyEvents(self);
		if (keyEvents == null || usedKeyEvents == null) return _super(self).onKey(v, keyCode, e);

		KeyCode targetCode = mapKeyCode(keyCode);

		if (targetCode != null) {
			int  action = e.getAction();
			long time   = System.nanoTime();

			if (action == KeyEvent.ACTION_DOWN) {
				e.startTracking();
			}

			// 【修复】必须对 keyEvents 列表加锁，因为 Arc 的主循环也是 synchronized(keyEvents)
			synchronized (keyEvents) {
				if (action == KeyEvent.ACTION_DOWN) {
					injectEvent(usedKeyEvents, keyEvents, time, KEY_DOWN, targetCode, (char) 0);

					// 处理特殊 TYPED 事件
					if (targetCode == KeyCode.backspace) {
						injectEvent(usedKeyEvents, keyEvents, time, KEY_TYPED, KeyCode.unknown, '\b');
					} else if (targetCode == KeyCode.forwardDel) {
						injectEvent(usedKeyEvents, keyEvents, time, KEY_TYPED, KeyCode.unknown, (char) 127);
					}
				} else if (action == KeyEvent.ACTION_UP) {
					injectEvent(usedKeyEvents, keyEvents, time, KEY_UP, targetCode, (char) 0);
				}
			}

			if (Core.graphics != null) {
				Core.graphics.requestRendering();
			}
			return true;
		}

		// 其他普通按键（字母、数字等）交给原生的处理逻辑，避免破坏正常的文本输入
		return _super(self).onKey(v, keyCode, e);
	}
	public static KeyCode mapKeyCode(int keyCode) {
		// case KeyEvent.KEYCODE_SHIFT_LEFT -> KeyCode.shiftLeft;
		// case KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyCode.shiftRight;
		// case KeyEvent.KEYCODE_ESCAPE -> KeyCode.escape;
		return switch (keyCode) {
			case KeyEvent.KEYCODE_CTRL_LEFT -> KeyCode.controlLeft;
			case KeyEvent.KEYCODE_CTRL_RIGHT -> KeyCode.controlRight;
			case KeyEvent.KEYCODE_ALT_LEFT -> KeyCode.altLeft;
			case KeyEvent.KEYCODE_ALT_RIGHT -> KeyCode.altRight;
			// case KeyEvent.KEYCODE_SHIFT_LEFT -> KeyCode.shiftLeft;
			// case KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyCode.shiftRight;
			case KeyEvent.KEYCODE_DEL -> KeyCode.backspace;
			case KeyEvent.KEYCODE_FORWARD_DEL -> KeyCode.forwardDel;
			case KeyEvent.KEYCODE_MOVE_HOME -> KeyCode.home;
			case KeyEvent.KEYCODE_MOVE_END -> KeyCode.end;
			case KeyEvent.KEYCODE_INSERT -> KeyCode.insert;
			case KeyEvent.KEYCODE_PAGE_UP -> KeyCode.pageUp;
			case KeyEvent.KEYCODE_PAGE_DOWN -> KeyCode.pageDown;
			// case KeyEvent.KEYCODE_ESCAPE -> KeyCode.escape;
			default -> null;
		};
	}

	private static Pool<Object> getUsedKeyEvents(AndroidInput self) {
		IdentityHashMap<String, Object> variable = allvariables.computeIfAbsent(self, _ -> new IdentityHashMap<>());
		@SuppressWarnings("unchecked")
		Pool<Object> usedKeyEvents = (Pool<Object>) variable.computeIfAbsent("usedKeyEvents", _ ->
		 FieldUtils.getOrNull(FieldUtils.getFieldAccess(AndroidInput.class, "usedKeyEvents"), self));
		return usedKeyEvents;
	}
	private static ArrayList<Object> getKeyEvents(AndroidInput self) {
		IdentityHashMap<String, Object> variable = allvariables.computeIfAbsent(self, _ -> new IdentityHashMap<>());

		@SuppressWarnings("unchecked")
		ArrayList<Object> keyEvents = (ArrayList<Object>) variable.computeIfAbsent("keyEvents", _ ->
		 FieldUtils.getOrNull(FieldUtils.getFieldAccess(AndroidInput.class, "keyEvents"), self));
		return keyEvents;
	}

	public static void injectEventGlobal(long time, int type, KeyCode code, char ch) {
		if (!(Core.input instanceof AndroidInput input)) return;
		injectEvent(getUsedKeyEvents(input), getKeyEvents(input), time, type, code, ch);
	}

	private static void injectEvent(Pool<Object> pool, ArrayList<Object> list,
	                                long time, int type, KeyCode code, char ch) {
		try {
			Object event = pool.obtain();
			UNSAFE.putLong(event, AndroidInput_KeyEvent.TIMESTAMP, time);
			UNSAFE.putInt(event, AndroidInput_KeyEvent.TYPE, type);
			UNSAFE.putObject(event, AndroidInput_KeyEvent.KEY_CODE, code);
			UNSAFE.putChar(event, AndroidInput_KeyEvent.KEY_CHAR, ch);
			list.add(event);
		} catch (Exception ex) {
			Log.err("InputFix inject error", ex);
		}
	}
	public static void load() {
		Class<?>            prevInput  = Core.input.getClass();
		LazyValue<Class<?>> inputClass = LazyValue.of(() -> AndroidInputFixInterface.visit(prevInput));
		Class<?>            prevApp    = Core.app.getClass();
		LazyValue<Class<?>> appClass   = LazyValue.of(() -> AndroidApplicationHookInterface.visit(prevApp));

		Runnable run = () -> {
			HopeReflect.changeClass(Core.input, R_Hook.android_input_fix ? inputClass.get() : prevInput);
			HopeReflect.changeClass(Core.app, R_Hook.android_input_fix ? appClass.get() : prevApp);
			Runnable r = R_Hook.android_input_fix ? WSAInputFixer::install : WSAInputFixer::uninstall;
			if (Vars.ui != null) {
				r.run();
			} else {
				Events.run(ClientLoadEvent.class, r);
			}
		};
		E_Hook.data.onChanged(E_Hook.android_input_fix.name(), run);
		run.run();
	}
}