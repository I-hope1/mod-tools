package modtools.utils;

import arc.struct.ObjectMap;
import arc.util.*;
import arc.util.Timer.Task;

import static modtools.utils.Tools.Sr;

public class TaskManager {
	private static final ObjectMap<Runnable, Task> map = new ObjectMap<>();

	public static Task newTask(Runnable run) {
		return new Task() {
			public void run() {
				run.run();
			}
		};
	}

	/** 新建任务
	 * 如果任务没有完成，不新建
	 * @param delay 单位tick (正常60tick/s)
	 * @return 进行的任务
	 *  */
	public static Task acquireTask(float delay, Runnable run) {
		return Sr(map.get(run, () -> Time.runTask(delay, run)))
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
		if (reSchedule(delaySeconds, task)) {
			return true;
		}
		task.cancel();
		return false;
	}

	public static boolean reSchedule(float delaySeconds, Task task) {
		if (task.isScheduled()) {
			return false;
		} else {
			Timer.schedule(task, delaySeconds);
			return true;
		}
	}
}
