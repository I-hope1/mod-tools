package modtools.utils;

import arc.func.Boolc;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.util.*;
import arc.util.Timer.Task;
import arc.util.pooling.Pool.Poolable;
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
		elem.addListener(new DoubleClick().init(click1, d_click1));
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
	public static String longPressOrRclickKey() {
		return mobile ? "Long press" : "Right click";
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
					boolc.get(true);
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
		public void clicked(InputEvent event, float x, float y) {
			// super.clicked(event, x, y);
			if (longPress) return;
			if (task.isScheduled() && pressed) boolc.get(false);
			task.cancel();
		}
	}
	public static class DoubleClick extends ClickListener implements Poolable {
		Runnable click, d_click;
		public DoubleClick() { }
		public DoubleClick init(Runnable click, Runnable d_click) {
			this.click = click;
			this.d_click = d_click;
			return this;
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
			if (click != null && d_click == null) {
				click.run();
				return;
			}
			if (TaskManager.scheduleOrCancel(0.3f, clickTask)) {
				last.set(mouseVec);
				return;
			}
			if (mouseVec.dst(last) < IntUI.MAX_OFF) d_click.run();
		}
		public void reset() {
			click = null;
			d_click = null;
			clickTask.cancel();
		}
	}
	private static class RightClickListener extends ClickListener {
		private final Runnable run;
		RightClickListener(Runnable run) { super(KeyCode.mouseRight);
			this.run = run;
		}
		public void clicked(InputEvent event, float x, float y) {
			if (event.stopped) return;
			run.run();
			event.stop();
		}
	}
}
