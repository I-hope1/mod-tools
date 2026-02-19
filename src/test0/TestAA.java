package test0;

import arc.scene.ui.Tooltip.Tooltips;
import modtools.ui.IntUI.ITooltip;
import nipx.annotation.*;

@Reloadable
public class TestAA {
	public static void main(String[] args) {
		byte b  = -1;
		int  ib = (int) b;
		System.out.println(ib * 2 + 323223);
		Runnable rNew = new Runnable() {
			@Override
			public void run() {
				System.out.println("rNew");
			}
		}; // 编译为 Main$1
		Runnable r1 = new Runnable() {
			@Override
			public void run() {
				System.out.println("r1");
			}
		};   // 变为了 Main$2
		Runnable r2 = () -> {
			rNew.run();
		}; // 依然是 lambda$main$0
		r2.run();
	}
	@OnReload
	public static void reload() {
		main(null);
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
	public static class ChildClass extends ParentClass2 {
	}


	static {
		Tooltips.getInstance().textProvider = text -> new ITooltip(t -> t.add(text));
	}
}
