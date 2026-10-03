package test4;

/** 极简替身：模仿 arc 的 Table.button(String, Runnable)。 */
public class Table {
	public void button(String label, Runnable action) {
		action.run();
	}
}
