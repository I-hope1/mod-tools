package modtools.struct;

import arc.func.Boolp;
import arc.struct.Seq;

public class TaskSet extends Seq<Boolp> {
	public void exec() {
		/* 为false，就删除 */
		removeAll(boolp -> !boolp.get());
	}
	/** 添加常驻任务 */
	public void add(Runnable run) {
		add(() -> {
			run.run();
			return true;
		});
	}
}
