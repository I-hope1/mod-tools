package modtools.events;

import arc.*;
import arc.func.*;
import arc.graphics.Color;
import arc.scene.style.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.IntUI;
import modtools.ui.content.SettingsUI.SettingsBuilder;
import modtools.utils.ElementUtils.MarkedCode;
import modtools.utils.Tools;

public class ExecuteTree {
	private static TaskNode context = null;

	public static Seq<TaskNode> all   = new Seq<>();
	public static Seq<TaskNode> roots = new Seq<>();

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
		return node(task, () -> {});
	}

	public static TaskNode node(String name, String source, Runnable task) {
		return node(task, name, source, Styles.none, () -> {});
	}

	public static TaskNode node(String source, Runnable task) {
		return node(task, source, () -> {});
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

	public static class TaskNode {
		public static final float perTick = 1 / 60f;

		public String code;
		public TaskNode code(String code) {
			this.code = code;
			return this;
		}
		private final Task task;
		public        Timer timer = Timer.instance();
		public TaskNode worldTimer() {
			timer = worldTimer;
			return this;
		}

		public Runnable run;
		public Drawable icon;
		public String   source;
		public String   name;

		public @Nullable TaskNode parent;

		public Status        status;
		public Seq<TaskNode> children = new Seq<>();

		float intervalSeconds = perTick;
		public TaskNode intervalSeconds(float intervalSeconds) {
			if (intervalSeconds < 0.001f) throw new IllegalArgumentException("intervalSeconds cannot be less than 0.001f");
			this.intervalSeconds = intervalSeconds;
			return this;
		}

		int repeatCount = 0;
		public int repeatCount() {
			return repeatCount;
		}
		public TaskNode repeatCount(int repeatCount) {
			this.repeatCount = repeatCount;
			return this;
		}
		public TaskNode forever() {
			repeatCount = -1;
			return this;
		}
		boolean resubmitted = false, editable = false;
		public boolean isResubmitted() {
			return resubmitted;
		}
		public TaskNode resubmitted() {
			editable = true;
			resubmitted = true;
			return this;
		}

		public TaskNode(@Nullable TaskNode parent, String name, Runnable run, Drawable icon, String source) {
			if (parent != null) {
				parent.children.add(this);
			}
			this.name = name;
			this.parent = parent;
			this.run = run;
			this.icon = icon;
			this.source = source;
			this.status = run == null ? NoTask.INSTANCE : new Paused();
			task = run == null ? null : new MyTask(run, source);
			all.add(this);
		}
		public void apply() {
			apply(false);
		}

		public void apply(boolean async) {
			if (!(task instanceof MyTask) && async) throw new IllegalStateException("async can be applied only for MyTask.");
			interrupt();
			if (async) {
				DelegateRun newRun = new DelegateRun(task, () -> intervalSeconds);
				((MyTask) task).delegate = newRun;
				IntVars.executor.execute(newRun);
				return;
			}
			timer.scheduleTask(task, 0, intervalSeconds, repeatCount);
		}
		public boolean running() {
			return task.isScheduled() || (task instanceof MyTask t && t.delegate != null);
		}
		public boolean editable() {
			return editable;
		}
		public void clear() {
			interrupt();
			children.clear();
			if (parent != null) parent.children.remove(this);
			else roots.remove(this);
			all.remove(this);
		}
		public void interrupt() {
			if (task != null) {
				if (task instanceof MyTask t && t.delegate != null) {
					t.delegate.stop();
				} else task.cancel();
				status = new Paused();
			}

		}

		private class MyTask extends Task {
			private final Runnable    run;
			private final String      source;
			private       DelegateRun delegate;
			public MyTask(Runnable run, String source) {
				this.run = run;
				this.source = source;
			}
			public void run() {
				try {
					if (status instanceof Paused paused) status = paused.run();
					run.run();
					repeatCount--;
					if (repeatCount == -1 && status instanceof Running running) {
						status = running.finish();
					}
				} catch (Throwable th) {
					Error error = new Error(th);
					error.print(source);
					status = error;
					cancel();
				}
			}
		}

		public void edit() {
			new SettingsBuilder(new Table()) {{
				number("@task.intervalseconds", f -> intervalSeconds = f, () -> intervalSeconds, 0.01f, Float.MAX_VALUE);
				check("@task.worldtimer", b -> timer = b ? Timer.instance() : worldTimer, () -> timer != Timer.instance());
				numberi("@task.repeatcount", i -> repeatCount = i, () -> repeatCount, -1, Integer.MAX_VALUE);
				IntUI.showSelectTable(Core.input.mouse().cpy(), (p, hide, search) -> {
					p.add(main).grow();
				}, false).hidden(() -> main.clearChildren());
			}};
		}
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
	public enum StatusEnum implements StatusInterface {
		/* 因为Icon(可能)还为赋值 */
		noTask(() -> Icon.none, Color.lightGray),
		paused(() -> Icon.bookOpen, Color.orange),
		running(() -> Icon.rotate, Color.pink),
		ok(() -> Icon.ok, Color.green),
		error(() -> Icon.cancel, Color.red);
		final Prov<TextureRegionDrawable> drawable;
		final Color                       color;
		StatusEnum(Prov<TextureRegionDrawable> drawable, Color color) {
			this.drawable = drawable;
			this.color = color;
		}
		private Drawable cache;
		public Drawable icon() {
			if (cache == null) cache = drawable.get().tint(color());
			return cache;
		}
		public Color color() {
			return color;
		}

		public int code() {
			return ordinal();
		}
	}
	abstract
	public static class Status implements StatusInterface {
		public final StatusEnum enum_ = StatusEnum.values()[code()];
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
