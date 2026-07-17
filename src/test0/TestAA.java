package test0;

import arc.graphics.Color;
import arc.scene.ui.Tooltip.Tooltips;
import arc.util.Log;
import modtools.ui.IntUI.ITooltip;
import modtools.ui.comp.Window;
import nipx.annotation.OnReload;

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

	public static void newWindow() {
		new Window("www") {{
			float size = 10 * 3 / 2f;
			float s = size * 1.9f;
		/* 	cont.table(t -> {
				t.add("aasasasa").width(100);
				t.image().size(30).color(Pal.muddy);
				t.image().size(size).color(Color.lightGray);
			}).pad(4); */
			cont.table(t -> t.add("1")).row();
			// cont.table(t -> t.add("2")).row();

			cont.image().growX().color(Color.cyan).colspan(2).row();
			cont.add("33333").growX();
			/* cont.image().size(s).color(Color.yellow);
			cont.image().size(42).color(Color.lightGray);
			cont.button("777", Styles.flatBordert, () -> Log.info("ojaso"))
			 .size(64).row();

			cont.image().size(42).color(Color.purple);
			cont.image().size(size).color(Color.white); */
		}}.show();
	}

	public static Runnable runx = () -> Log.info("run");
	@OnReload
	public static void reload() {
		Log.info("reload");
		runx.run();
		/* try (Arena arena = Arena.ofConfined()) {
			JNIEnv   env      = JNIEnv.getInstance(arena);
			JVMTIEnv jvmtiEnv = JVMTIEnv.getInstance();
			jvmtiEnv.walkCurrentThreadFrames(env, MemorySegment.NULL, 64, 0, (className, methodName, methodSig, thisAddress) -> {
				System.out.println(className + " " + methodName + " " + methodSig + " " + thisAddress);
			});
		} */
	}
	Runnable rx;
	public void say() {
		if (rx != null) rx.run();
		var x = 129323;
		rx = () -> {
			Log.info("rx" + rx);
			Log.info(x);
		};
	}


	static {
		Tooltips.getInstance().textProvider = text -> new ITooltip(t -> t.add(text));
	}
}
