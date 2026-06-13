package test0;

import arc.scene.ui.Tooltip.Tooltips;
import arc.util.Log;
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

	public static Runnable runx = () -> Log.info("run");
	@OnReload
	public static void reload() {
		Log.info("reload");
		runx.run();
		/* try (Arena arena = Arena.ofConfined()) {
			JNIEnv   env      = new JNIEnv(arena);
			JVMTIEnv jvmtiEnv = JVMTIEnv.getInstance();
			jvmtiEnv.walkCurrentThreadFrames(env, MemorySegment.NULL, 64, 0, (className, methodName, methodSig, thisAddress) -> {
				System.out.println(className + " " + methodName + " " + methodSig + " " + thisAddress);
			});
		} */
	}


	static {
		Tooltips.getInstance().textProvider = text -> new ITooltip(t -> t.add(text));
	}
}
