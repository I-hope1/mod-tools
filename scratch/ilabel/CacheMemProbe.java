package modtools.ui.comp.input;

import arc.struct.IntSeq;

import java.lang.management.ManagementFactory;

/**
 * 量「每个实例一份命中缓存」的内存代价。
 * <p>用 {@code ThreadMXBean.getThreadAllocatedBytes} 数分配字节，不受 GC 波动影响。
 */
public class CacheMemProbe {
	/** 当前写法：字段直接摊在实例上 */
	static class A {
		final IntSeq runStart = new IntSeq();
		boolean      dirty    = true;
		float        lineH, relMinX, relMaxX, relMinY, relMaxY, lastX = Float.NaN, lastY;
		int          lastCursor;
	}

	/** 懒加载：没人查过就只有一个 null 引用 */
	static class B {
		Hit     hit;
		boolean dirty = true;
	}

	static class Hit {
		final IntSeq runStart = new IntSeq();
		float        lineH, relMinX, relMaxX, relMinY, relMaxY, lastX = Float.NaN, lastY;
		int          lastCursor;
	}

	/** 只有 IntSeq 本身，用来拆开看默认容量占了多少 */
	static class C {
		final IntSeq runStart = new IntSeq();
	}

	static class D {
		boolean dirty = true;
	}

	static final com.sun.management.ThreadMXBean MX =
	 (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	static long alloc() {
		return MX.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	static Object[] keep = new Object[1];

	/** 跑一遍，返回字节/实例 */
	static double perInstance(int kind, int n) {
		long b0 = alloc();
		for (int i = 0; i < n; i++) {
			keep[0] = switch (kind) {
				case 0 -> new A();
				case 1 -> new B();
				case 2 -> new Hit();
				case 3 -> new C();
				default -> new D();
			};
		}
		long b1 = alloc();
		return (b1 - b0) / (double) n;
	}

	public static void main(String[] args) {
		int n = 200_000;
		// 预热，避免第一次的类型检查/类加载混进分配计数
		for (int k = 0; k < 5; k++) for (int i = 0; i < 1000; i++) perInstance(k, 1000);

		double d  = perInstance(4, n);   // 只有 dirty 标志
		double c  = perInstance(3, n);   // 只有 IntSeq
		double a  = perInstance(0, n);   // 现在的写法
		double b  = perInstance(1, n);   // 懒加载未使用
		double h  = perInstance(2, n);   // 懒加载已被悬停

		System.out.printf("D 只有 dirty 标志           : %6.1f 字节/实例%n", d);
		System.out.printf("C 只多一个 IntSeq           : %6.1f 字节/实例（IntSeq 本身 %.1f）%n", c, c - d);
		System.out.printf("A 现在的写法                : %6.1f 字节/实例%n", a);
		System.out.printf("B 懒加载·从未被悬停          : %6.1f 字节/实例%n", b);
		System.out.printf("H 懒加载·被悬停后（Hit 对象） : %6.1f 字节/实例%n", h);
		System.out.println();
		System.out.printf("从未被悬停时，每个实例省 %.0f 字节%n", a - b);
		for (int count : new int[]{1_000, 10_000, 100_000}) {
			System.out.printf("  %,7d 个 label：%.2f MB → %.2f MB%n", count,
			                  a * count / 1048576.0, b * count / 1048576.0);
		}
		System.out.printf("被悬停之后：%.0f vs %.0f 字节/实例（多一次跳转，内存基本持平）%n", a, b + h);
	}
}
