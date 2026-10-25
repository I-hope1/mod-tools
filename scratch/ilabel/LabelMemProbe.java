package modtools.ui.comp.input;

import arc.graphics.Color;
import arc.scene.ui.Label.LabelStyle;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;

/** 量真实 {@link InlineLabel} 的每实例分配字节，验证命中缓存是否真的按需分配 */
public class LabelMemProbe {
	static final com.sun.management.ThreadMXBean MX =
	 (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	static long alloc() {
		return MX.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	public static void main(String[] args) throws Exception {
		LabelStyle style = new LabelStyle(HitTestProbe.font(), Color.white);

		// 预热：让类加载、常量池、JIT 的分配都发生在计时之前
		for (int i = 0; i < 3000; i++) new InlineLabel("abcdef", style);

		int          n     = 20_000;
		InlineLabel[] keep = new InlineLabel[n];
		long          b0   = alloc();
		for (int i = 0; i < n; i++) keep[i] = new InlineLabel("abcdef", style);
		long b1 = alloc();

		System.out.printf("每个 InlineLabel 分配 %.1f 字节%n", (b1 - b0) / (double) n);

		// 缓存字段必须还是 null：证明没有为「从没被悬停过」的 label 分配 IntSeq
		Field hitF  = InlineLabel.class.getDeclaredField("hit");
		Field runsF = InlineLabel.class.getDeclaredField("pendingRuns");
		hitF.setAccessible(true);
		runsF.setAccessible(true);
		int hitCount = 0, runCount = 0;
		for (InlineLabel l : keep) {
			if (hitF.get(l) != null) hitCount++;
			if (runsF.get(l) != null) runCount++;
		}
		System.out.printf("构造后 hit != null 的：%d / %d；pendingRuns != null 的：%d / %d%n",
		                  hitCount, n, runCount, n);
	}
}
