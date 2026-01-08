package modtools.utils;

import arc.func.*;
import arc.struct.ObjectMap;
import arc.util.*;
import arc.util.Timer.Task;

public class TaskManager {
	private static final ObjectMap<Runnable, Task> map = new ObjectMap<>();

	public static Task newTask(Runnable run) {
		return new Task() {
			public void run() {
				run.run();
			}
		};
	}
	public static Task newTaskc(Cons<Task> cons) {
		return new Task() {
			public void run() {
				cons.get(this);
			}
		};
	}

	/**
	 * <p>新建任务{@link Time#runTask(float, Runnable)}
	 * <p>如果任务没有完成，不新建
	 * @param delay 单位tick (正常60tick/s)
	 * @return 进行的任务
	 * @see Time#runTask(float, Runnable)
	 *  */
	public static Task acquireTask(float delay, Runnable run) {
		return SR.of(map.get(run, () -> Time.runTask(delay, run)))
		 .consNot(Task::isScheduled, task -> Timer.schedule(task, delay / 60f)).get();
	}
	/** @param delay 延迟的帧（tick，60tick/s) */
	public static boolean scheduleOrCancel(int delay, Runnable run) {
		return scheduleOrCancel(delay / 60f, run);
	}


	public static boolean scheduleOrCancel(float delaySeconds, Runnable run) {
		return scheduleOrCancel(delaySeconds, map.get(run, () -> Timer.schedule(run, delaySeconds)));
	}
	/**
	 * @param task 执行代码
	 *
	 * @return 是否新建了任务
	 */
	public static boolean scheduleOrCancel(float delaySeconds, Task task) {
		if (trySchedule(delaySeconds, task)) {
			return true;
		}
		task.cancel();
		return false;
	}
	/** 将task添加到计时器  */
	public static void  scheduleOrReset(float delaySeconds, Runnable run) {
		scheduleOrReset(delaySeconds, map.get(run, () -> acquireTask(delaySeconds * 60f, run)));
	}
	/** 将task添加到计时器  */
	public static void scheduleOrReset(float delaySeconds, Task task) {
		if (task.isScheduled()) {
			task.cancel();
		}
		Timer.schedule(task, delaySeconds);
	}

	/** 尝试添加任务
	 * @return true 如果添加成功 */
	public static boolean trySchedule(float delaySeconds, Task task) {
		if (task.isScheduled()) {
			return false;
		} else {
			Timer.schedule(task, delaySeconds);
			return true;
		}
	}
	/**
	 * 重复运行直到返回{@code true}
	 * @param boolp 布尔提供者
	 */
	public static void forceRun(Boolp boolp) {
		Timer.schedule(new Task() {
			public void run() {
				try {
					if (boolp.get()) cancel();
				} catch (Throwable e) {
					Log.err(e);
					cancel();
				}
			}
		}, 0f, 0.5f, -1);
	}
}
