package modtools.events;

import arc.func.Floatp;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.struct.MySet;
import modtools.utils.ui.ReflectTools.MarkedCode;
import modtools.utils.Tools;

public class ExecuteTree {
	private static TaskNode context = null;


	public static MySet<TaskNode> all   = new MySet<>();
	public static MySet<TaskNode> roots = new MySet<>();

	public static TaskNode nodeRoot(Runnable task, String name, String source, Drawable icon, Runnable children) {
		var root = node(task, name, source, icon, children);
		root.name = name;
		roots.add(root);
		return root;
	}

	public static TaskNode node(Runnable task, String name, String source, Drawable icon, Runnable children) {
		TaskNode node = new TaskNode(context, name, task, icon, source);

		TaskNode prev = context;
		context = node;
		children.run();
		context = prev;

		return node;
	}
	public static TaskNode node(Runnable task, String source, Runnable children) {
		return node(task, "", source, Styles.none, children);
	}

	public static TaskNode node(Runnable task, Runnable children) {
		return node(task, "<internal>", children);
	}

	public static TaskNode node(Runnable task) {
		return node(task, IntVars.EMPTY_RUN);
	}

	public static TaskNode node(String name, String source, Runnable task) {
		return node(task, name, source, Styles.none, IntVars.EMPTY_RUN);
	}

	public static TaskNode node(String source, Runnable task) {
		return node(task, source, IntVars.EMPTY_RUN);
	}

	public static @Nullable TaskNode context() {
		return context;
	}
	public static void context(TaskNode node, Runnable children) {
		TaskNode prev = context;
		context = node;
		children.run();
		context = prev;
	}

	public static Timer worldTimer = new Timer();

	static {
		Tools.TASKS.add(() -> {
			if (Vars.state.isPaused()) worldTimer.stop();
			else worldTimer.start();
		});
	}
	public interface StatusInterface extends MarkedCode {
		Color color();
		default Drawable icon() {return Icon.warning.tint(color());}
	}
	abstract sealed
	public static class Status implements StatusInterface {
		public final StatusList enum_ = StatusList.values()[code()];
		public String name() {
			return enum_.name();
		}
		public Color color() {
			return enum_.color();
		}
		public Drawable icon() {
			return enum_.icon();
		}
	}

	public static final class NoTask extends Status {
		public static final NoTask INSTANCE = new NoTask();
		public int code() {
			return 0;
		}
	}
	public static final class Paused extends Status {
		public Running run() {
			return new Running(Time.time);
		}
		public int code() {
			return 1;
		}
	}
	public static final class Running extends Status {
		float startTime;
		public Running(float startTime) {
			this.startTime = startTime;
		}
		public OK finish() {
			return new OK(startTime, Time.time);
		}
		public int code() {
			return 2;
		}
	}
	public static final class OK extends Status {
		float startTime, finishedTime;
		public OK(float startTime, float finishedTime) {
			this.startTime = startTime;
			this.finishedTime = finishedTime;
		}
		public int code() {
			return 3;
		}
	}
	public static final class Error extends Status {
		public Throwable th;
		public Error(Throwable th) {
			this.th = th;
		}
		public void print(String source) {
			Log.err("Exception in running " + source, th);
		}
		public int code() {
			return 4;
		}
	}


	public static class JSRun extends DelegateRun {
		public String code;
		public JSRun(Runnable delegate, Floatp intervalSeconds, String code) {
			super(delegate, intervalSeconds);
			this.code = code;
		}
	}
	public static class DelegateRun implements Runnable {
		Runnable delegate;
		Floatp   intervalSeconds;
		public DelegateRun(Runnable delegate, Floatp intervalSeconds) {
			this.delegate = delegate;
			this.intervalSeconds = intervalSeconds;
		}
		public void run() {
			if (delegate == null) return;
			delegate.run();
			/* sec -> ms */
			float v = intervalSeconds.get() * 1000;
			Threads.sleep((long) v, (int) (v - ((long) v) * 100000));
			run();
		}
		public void stop() {
			delegate = null;
		}
	}
}
