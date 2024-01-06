package modtools.struct;

import arc.func.Boolp;

public class TaskSet extends MySet<Boolp> {
	public void exec() {
		/* 为false，就删除 */
		filter(Boolp::get);
	}
	/** 添加常驻任务 */
	public void add(Runnable run) {
		add(() -> {
			run.run();
			return true;
		});
	}
}
