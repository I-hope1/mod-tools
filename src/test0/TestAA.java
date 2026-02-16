package test0;

import arc.scene.ui.Tooltip.Tooltips;
import modtools.ui.IntUI.ITooltip;

public class TestAA {
	public static void main(String[] args) {
		byte b = -1;
		int ib = (int)b;
		System.out.println(".,.asas");
		System.out.println("aiasj");
		System.out.println("啊是阿三");
		System.out.println(ib);
		// Runnable r = () -> {
		// 	System.out.println("asas");
		// };
		// r.run();
	}


	static {
		Tooltips.getInstance().textProvider = text -> new ITooltip(t -> t.add(text));
	}
}
