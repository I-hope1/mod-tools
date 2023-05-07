package modtools.utils;

import arc.func.Boolp;

public class TaskSet extends MySet<Boolp> {
	public void exec() {
		filter(Boolp::get);
	}
}
