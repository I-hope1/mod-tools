package modtools.struct;

import arc.func.Boolp;
import arc.struct.Seq;
import arc.util.Log;

/** 为false，就删除任务 */
public class TaskSet extends Seq<Boolp> {
	/** boolp.get()为{@code false}，就删除 */
	public void exec() {
		removeAll(boolp -> {
			try {
				return !boolp.get();
			} catch (Throwable e) {
				Log.err("Failed to run " + boolp, e);
				return false;
			}
		});
	}
	/** 添加常驻任务 */
	public void add(Runnable run) {
		add(() -> {
			run.run();
			return true;
		});
	}
}
