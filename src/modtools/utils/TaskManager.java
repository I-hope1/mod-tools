package modtools.utils;

import arc.func.*;
import arc.util.*;
import arc.util.Timer.Task;

public class TaskManager {

	/** 快捷创建任务 */
	public static Task newTask(Runnable run) {
		return new Task() {
			@Override
			public void run() {
				run.run();
			}
		};
	}

	/** 快捷创建可拿到自身 task 的任务（方便自取消） */
	public static Task newTaskc(Cons<Task> cons) {
		return new Task() {
			@Override
			public void run() {
				cons.get(this);
			}
		};
	}

	/**
	 * 防抖/重置任务：如果正在排队则取消并重新倒计时。
	 * 注：Timer.schedule 与 cancel 内部已线程安全，无需额外加锁。
	 */
	public static void reset(Task task, float delaySeconds) {
		synchronized (task) {
			if (task.isScheduled()) {
				task.cancel();
			}
			Timer.schedule(task, delaySeconds);
		}
	}

	public static void resetTicks(Task task, float delayTicks) {
		reset(task, delayTicks / 60f);
	}

	/**
	 * 启停任务（Toggle）：排队中则取消，未排队则调度。
	 * @return true 表示启动了调度；false 表示取消了调度
	 */
	public static boolean toggle(Task task, float delaySeconds) {
		synchronized (task) {
			if (task.isScheduled()) {
				task.cancel();
				return false;
			} else {
				Timer.schedule(task, delaySeconds);
				return true;
			}
		}
	}

	public static boolean toggleTicks(Task task, float delayTicks) {
		return toggle(task, delayTicks / 60f);
	}

	/**
	 * 尝试调度：只有未在排队时才添加调度。
	 * @return true 表示添加成功；false 表示已有排队中任务，未作处理
	 */
	public static boolean trySchedule(Task task, float delaySeconds) {
		synchronized (task) {
			if (task.isScheduled()) {
				return false;
			} else {
				Timer.schedule(task, delaySeconds);
				return true;
			}
		}
	}

	public static boolean tryScheduleTicks(Task task, float delayTicks) {
		return trySchedule(task, delayTicks / 60f);
	}

	/** 轮询等待重试 */
	public static Task forceRun(float intervalSeconds, int maxRetries, Boolp boolp) {
		return Timer.schedule(new Task() {
			int retries = 0;
			@Override
			public void run() {
				try {
					if (boolp.get()) {
						cancel();
						return;
					}
				} catch (Throwable e) {
					Log.err("Error in forceRun", e);
				}
				if (maxRetries > 0 && ++retries >= maxRetries) {
					cancel();
				}
			}
		}, 0f, intervalSeconds);
	}

	public static void runWhen(Boolp boolp, Runnable run) {
		Tools.TASKS.add(() -> {
			if (!boolp.get()) return true;
			run.run();
			return false;
		});
	}
}