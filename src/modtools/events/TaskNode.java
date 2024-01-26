package modtools.events;

import arc.Core;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.*;
import arc.util.Timer.Task;
import modtools.IntVars;
import modtools.IntVars.Async;
import modtools.events.ExecuteTree.*;
import modtools.events.ExecuteTree.Error;
import modtools.struct.MySet;
import modtools.ui.IntUI;
import modtools.ui.content.SettingsUI.SettingsBuilder;

public class TaskNode {
	public static final float perTick = 1 / 60f;

	public String code;
	public TaskNode code(String code) {
		this.code = code;
		return this;
	}
	private final Task  task;
	public        Timer timer = Timer.instance();
	public TaskNode worldTimer() {
		timer = ExecuteTree.worldTimer;
		return this;
	}

	public Runnable run;
	public Drawable icon;
	public String   source;
	public String   name;

	public @Nullable TaskNode parent;

	public Status          status;
	public MySet<TaskNode> children = new MySet<>();

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
		ExecuteTree.all.add(this);
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
			Async.executor.execute(newRun);
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
		else ExecuteTree.roots.remove(this);
		ExecuteTree.all.remove(this);
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
			check("@task.worldtimer", b -> timer = b ? Timer.instance() : ExecuteTree.worldTimer, () -> timer != Timer.instance());
			numberi("@task.repeatcount", i -> repeatCount = i, () -> repeatCount, -1, Integer.MAX_VALUE);
			IntUI.showSelectTable(Core.input.mouse().cpy(), (p, hide, search) -> {
				p.add(main).grow();
			}, false).hidden(() -> main.clearChildren());
		}};
	}
}
