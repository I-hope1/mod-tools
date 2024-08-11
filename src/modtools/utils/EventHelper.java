package modtools.utils;

import arc.Core;
import arc.func.*;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.event.InputEvent.InputEventType;
import arc.scene.ui.Label;
import arc.util.*;
import arc.util.Timer.Task;
import arc.util.pooling.Pools;
import mindustry.Vars;
import modtools.ui.IntUI;

import java.util.function.Consumer;

import static mindustry.Vars.mobile;
import static modtools.IntVars.mouseVec;
import static modtools.utils.Tools.runT;

public class EventHelper {
	static final Vec2 last = new Vec2();

	public static <T extends Element> T
	longPress0(T elem, final Runnable run) {
		return longPress0(elem, IntUI.DEF_LONGPRESS, run);
	}
	/**
	 * <p>创建一个双击事件</p>
	 * <p color="gray">我还做了位置偏移计算，防止误触</p>
	 * @param <T>     the type parameter
	 * @param elem    被添加侦听器的元素
	 * @param click   单击事件
	 * @param d_click 双击事件
	 * @return the t
	 */
	public static <T extends Element> T
	doubleClick(T elem, Runnable click, Runnable d_click) {
		if (click == null && d_click == null) return elem;
		final Runnable
		 // 单机
		 click1 = click == null ? null : runT(click::run),
		 // 双击
		 d_click1 = d_click == null ? null : runT(d_click::run);
		elem.addListener(new DoubleClick(click1, d_click1));
		return elem;
	}
	/**
	 * 长按事件
	 * @param <T>      the type parameter
	 * @param elem     被添加侦听器的元素
	 * @param duration 需要长按的事件（单位毫秒[ms]，600ms=0.6s）
	 * @param boolc0   {@link Boolc#get(boolean b)}形参{@code b}为是否长按
	 * @return the t
	 */
	public static <T extends Element> T
	longPress(T elem, final long duration, final Boolc boolc0) {
		Boolc boolc = b -> {
			try {
				boolc0.get(b);
			} catch (Throwable e) {
				Log.err(e);
				IntUI.showException(e);
			}
		};
		elem.addCaptureListener(new LongPressListener(boolc, duration));
		return elem;
	}
	public static <T extends Element> T
	longPress(T elem, final Boolc boolc) {
		return longPress(elem, IntUI.DEF_LONGPRESS, boolc);
	}
	/**
	 * 长按事件
	 * @param <T>      the type parameter
	 * @param elem     被添加侦听器的元素
	 * @param duration 需要长按的事件（单位毫秒[ms]，600ms=0.6s）
	 * @param run      长按时调用
	 * @return the t
	 */
	public static <T extends Element> T
	longPress0(T elem, long duration, Runnable run) {
		return longPress(elem, duration, b -> {
			if (b) run.run();
		});
	}
	/**
	 * 添加右键事件
	 * @param <T>  the type parameter
	 * @param elem 被添加侦听器的元素
	 * @param run  右键执行的代码
	 * @return the t
	 */
	public static <T extends Element> T
	rightClick(T elem, Runnable run) {
		elem.addListener(new RightClickListener(run));
		return elem;
	}
	/**
	 * <p>long press for {@link Vars#mobile moblie}</p>
	 * <p>r-click for desktop</p>
	 * @param <T>     the type parameter
	 * @param element the element
	 * @param run     the run
	 * @return the t
	 */
	@SuppressWarnings("UnusedReturnValue")
	public static <T extends Element> T
	longPressOrRclick(T element, Consumer<T> run) {
		return mobile ? longPress0(element, () -> run.accept(element))
		 : rightClick(element, () -> run.accept(element));
	}
	public static <T> void
	longPressOrRclick(Element listener, Class<T> target, Consumer<T> cons) {
		listener.addListener(mobile ? new LongPressListener(null, IntUI.DEF_LONGPRESS) {
			public void longPress(InputEvent event, float x, float y) {
				T targetActor = ElementUtils.findParent(event.targetActor, target);
				if (targetActor != null) cons.accept(targetActor);
				event.stop();
			}
		} : new ClickListener(KeyCode.mouseRight) {
			public void clicked(InputEvent event, float x, float y) {
				T targetActor = ElementUtils.findParent(event.targetActor, target);
				if (targetActor != null) cons.accept(targetActor);
				event.stop();
			}
		});
	}
	public static String longPressOrRclickKey() {
		return mobile ? "Long press" : "Right click";
	}
	/** 双击复制文本内容 */
	public static void addDClickCopy(Label label) {
		addDClickCopy(label, null);
	}
	public static void addDClickCopy(Label label, Func<String, String> func) {
		doubleClick(label, null, () -> {
			String s = String.valueOf(label.getText());
			JSFunc.copyText(func != null ? func.get(s) : s, label);
		});
	}

	public static <E> void addDClickCopy(Element element, Class<E> type, Func<E, String> func) {
		addDClickCopy(element, type::isInstance, (Func<Element, String>) func);
	}

	public static void addDClickCopy(Element element, Boolf<Element> boolf, Func<Element, String> func) {
		element.addListener(new DoubleClick(null, null) {
			public void d_clicked(InputEvent event, float x, float y) {
				Element e = event.targetActor;
				if (boolf.get(e)) {
					JSFunc.copyText(func.get(e), e);
				}
			}
		});
	}

	public static InputEvent obtainEvent(
	 InputEventType type, float x, float y,
	 int pointer, KeyCode keyCode) {
		InputEvent event = Pools.obtain(InputEvent.class, InputEvent::new);
		event.type = type;
		event.stageX = x;
		event.stageY = y;
		event.pointer = pointer;
		event.keyCode = keyCode;
		return event;
	}

	public static class LongPressListener extends ClickListener {
		final long  duration;
		final Boolc boolc;
		public LongPressListener(Boolc boolc0, long duration0) {
			boolc = boolc0;
			duration = duration0;
			task = TaskManager.newTask(() -> {
				if (pressed && mouseVec.dst(last) < IntUI.MAX_OFF) {
					longPress = true;
					Core.scene.touchUp((int) mouseVec.x, (int) mouseVec.y, pressedPointer, button);
				}
			});
		}
		boolean longPress;
		final Task task;
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			if (event.stopped) return false;
			longPress = false;
			if (super.touchDown(event, x, y, pointer, button)) {
				last.set(mouseVec);
				task.cancel();
				Timer.schedule(task, duration / 1000f);
				return true;
			}
			return false;
		}
		public void touchDragged(InputEvent event, float x, float y, int pointer) {
			if (mouseVec.dst(last) >= IntUI.MAX_OFF && task != null) task.cancel();
		}
		public void longPress(InputEvent event, float x, float y) {
			boolc.get(true);
		}
		public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
			super.touchUp(event, x, y, pointer, button);
			if (longPress) longPress(event, x, y);
			task.cancel();
		}
		public void clicked(InputEvent event, float x, float y) {
			// 如果触发了长按，就不触发单击
			if (longPress) return;

			if (task.isScheduled() && pressed) boolc.get(false);
			task.cancel();
		}
	}
	public static class DoubleClick extends ClickListener {
		Runnable click, d_click;
		public DoubleClick(Runnable click, Runnable d_click) {
			this.click = click;
			this.d_click = d_click;
		}
		final Task clickTask = TaskManager.newTask(() -> {
			if (click != null) click.run();
		});

		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			last.set(mouseVec);
			return super.touchDown(event, x, y, pointer, button);
		}
		public void clicked(InputEvent event, float x, float y) {
			if (last.dst(mouseVec) > IntUI.MAX_OFF) return;
			super.clicked(event, x, y);
			// 至少满足一个，可能是个坑
			if (click != null && d_click == null) {
				click.run();
				return;
			}
			if (TaskManager.scheduleOrCancel(0.3f, clickTask)) {
				last.set(mouseVec);
				return;
			}
			if (mouseVec.dst(last) < IntUI.MAX_OFF) d_clicked(event, x, y);
		}
		public void d_clicked(InputEvent event, float x, float y) {
			d_click.run();
		}
	}
	public static class RightClickListener extends ClickListener {
		private final Runnable run;
		RightClickListener(Runnable run) {
			super(arc.input.KeyCode.mouseRight);
			this.run = run;
		}
		public void clicked(InputEvent event, float x, float y) {
			if (event.stopped) return;
			run.run();
			event.stop();
		}
	}
}
