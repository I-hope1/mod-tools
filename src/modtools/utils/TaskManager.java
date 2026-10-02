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
	 * 只用于串行化 TaskManager 的调用方；绝不能在 Timer 内部路径上被获取。
	 * Timer 线程持有的锁（threadLock、Timer 实例、task）与此锁完全无交集，不会形成死锁环。
	 */
	private static final Object LOCK = new Object();

	/**
	 * 防抖/重置任务：无论是否在排队，都取消并重新倒计时。
	 * Task.cancel() 在 timer == null 时也安全，可直接调用而无需先判断 isScheduled()。
	 */
	public static void reset(Task task, float delaySeconds) {
		synchronized (LOCK) {
			task.cancel();
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
		synchronized (LOCK) {
			if (task.isScheduled()) {
				task.cancel();
				return false;
			}
			Timer.schedule(task, delaySeconds);
			return true;
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
		synchronized (LOCK) {
			if (task.isScheduled()) return false;
			Timer.schedule(task, delaySeconds);
			return true;
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