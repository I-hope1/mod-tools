package test0;

import arc.scene.ui.Tooltip.Tooltips;
import modtools.ui.IntUI.ITooltip;

public class TestAA {
	public static void main(String[] args) {
		byte b = -1;
		int ib = (int)b;
		System.out.println("哦莎简欧i啥");
		System.out.println("aiasj");
		System.out.println("acu按时间哦");
		System.out.println(ib);
		// Runnable r = () -> {
		// 	System.out.println("asas");
		// };
		// r.run();
		new ChildClass().print();
	}
	public static class ParentClass1 {
		public void print() {
			System.out.println("11");
		}
	}
	public static class ParentClass2 {
		public void print() {
			System.out.println("22");
		}
	}
	public static class ChildClass extends ParentClass2{
	}



	static {
		Tooltips.getInstance().textProvider = text -> new ITooltip(t -> t.add(text));
	}
}
