package modtools.utils.search;

import arc.struct.IntIntMap;
import arc.struct.IntSeq;

/**
 * {@code ValueLabel.hover} 里「找最后一个 startIndex <= cursor」的两种写法：
 * <ol>
 *   <li>{@link #binary}：在 {@code sortedKeys} 上二分（当前实现）；</li>
 *   <li>{@link #reverse}：从尾往前扫，遇到第一个 {@code <= cursor} 就 break。</li>
 * </ol>
 * 两者语义相同（块不重叠且按 start 升序），差别只在探测次数。
 */
public class HitTestBench {
	static volatile int sink;

	/** 当前实现：二分找最后一个 key <= cursor，再查一次 endIndexMap */
	static int binary(IntSeq keys, IntIntMap ends, int cursor) {
		int left = 0, right = keys.size - 1, target = -1;
		while (left <= right) {
			int mid    = left + (right - left) / 2;
			int midKey = keys.get(mid);
			if (midKey <= cursor) {
				target = mid;
				left = mid + 1;
			} else {
				right = mid - 1;
			}
		}
		if (target == -1) return -1;
		int index = keys.get(target);
		int to    = ends.get(index);
		return cursor <= to ? index : -1;
	}

	/** 提案：倒序遍历 + continue，找到第一个 <= cursor 就 break */
	static int reverse(IntSeq keys, IntIntMap ends, int cursor) {
		for (int i = keys.size - 1; i >= 0; i--) {
			int index = keys.get(i);
			if (index > cursor) continue;
			int to = ends.get(index);
			return cursor <= to ? index : -1;   // 无论命中与否都 break
		}
		return -1;
	}

	/** 折中：先探最后一个块（倒序唯一的优势场景），不是再二分 */
	static int hybrid(IntSeq keys, IntIntMap ends, int cursor) {
		int last = keys.size - 1;
		if (keys.get(last) <= cursor) {
			int to = ends.get(keys.get(last));
			return cursor <= to ? keys.get(last) : -1;
		}
		return binary(keys, ends, cursor);
	}

	/**
	 * 从右端倍增试探，再在小窗口里二分。
	 * <p>代价是 O(log 距离)，距离 = 答案离末尾多远 —— 光标落在末尾几个块时只要 1~3 次访问，
	 * 落在最左边时约 2×log k（比纯二分多一点），两头都不会退化到 O(k)。
	 */
	static int gallop(IntSeq keys, IntIntMap ends, int cursor) {
		int last = keys.size - 1;
		if (keys.get(last) <= cursor) return hit(keys, ends, cursor, last);

		int lo   = last;   // 不变式：所有 j >= lo 都满足 keys[j] > cursor
		int step = 1;
		while (true) {
			int i = last - step;
			// 倍增会跳过 [0, lo-1] 里的位置（步长 1,2,4…），越界后要在这个窗口里二分
			if (i < 0) return keys.get(0) > cursor ? -1 : binaryRange(keys, ends, cursor, 0, lo - 1);
			if (keys.get(i) <= cursor) return binaryRange(keys, ends, cursor, i, lo - 1);
			lo = i;
			step <<= 1;
		}
	}

	/** 在 [left, right] 里找最后一个 <= cursor 的位置 */
	static int binaryRange(IntSeq keys, IntIntMap ends, int cursor, int left, int right) {
		int target = -1;
		while (left <= right) {
			int mid = left + (right - left) / 2;
			if (keys.get(mid) <= cursor) {
				target = mid;
				left = mid + 1;
			} else {
				right = mid - 1;
			}
		}
		return target == -1 ? -1 : hit(keys, ends, cursor, target);
	}

	static int hit(IntSeq keys, IntIntMap ends, int cursor, int idx) {
		int index = keys.get(idx);
		int to    = ends.get(index);
		return cursor <= to ? index : -1;
	}

	/** 探针次数（数组访问次数），用来解释耗时差异 */	static int probes(IntSeq keys, IntIntMap ends, int cursor, boolean rev) {
		int n = 0;
		if (rev) {
			for (int i = keys.size - 1; i >= 0; i--) {
				n++;
				if (keys.get(i) <= cursor) break;
			}
		} else {
			int left = 0, right = keys.size - 1;
			while (left <= right) {
				n++;
				int mid = left + (right - left) / 2;
				if (keys.get(mid) <= cursor) left = mid + 1;
				else right = mid - 1;
			}
		}
		return n;
	}

	/** k 个宽 3、间隔 10 的块：start = 0,10,20...；end = start+2 */
	static IntSeq keysOf(int k) {
		IntSeq s = new IntSeq();
		for (int i = 0; i < k; i++) s.add(i * 10);
		return s;
	}
	static IntIntMap endsOf(int k) {
		IntIntMap m = new IntIntMap();
		for (int i = 0; i < k; i++) m.put(i * 10, i * 10 + 2);
		return m;
	}

	/** 长度必须是 2 的幂，用 & 取模，避免取模本身影响计时 */
	static int[] cursors(int kind, int k, int len) {
		int[] out = new int[len];
		int   textLen = k * 10;
		for (int i = 0; i < len; i++) {
			out[i] = switch (kind) {
				case 0 -> (int) ((i * 2654435761L >>> 8) % textLen);   // 均匀
				case 1 -> textLen - 1;                                 // 最右：最后一个块
				case 2 -> 1;                                           // 最左：第一个块（倒序最坏）
				case 3 -> (i * 10 + 5) % textLen;                      // 全落在空隙里（无命中）
				// 4/5：偏右分布 —— 「hover 最里面的值」= 光标落在最后 1/10、1/4 的块里
				case 4 -> tailChunk(k, i, 10);
				default -> tailChunk(k, i, 4);
			};
		}
		return out;
	}

	/** 在最后 1/part 的块里均匀取一个，块内取中间 */
	static int tailChunk(int k, int i, int part) {
		int span = Math.max(1, k / part);
		int j    = k - 1 - (i % span);
		return j * 10 + 1;
	}

	static long bench(IntSeq keys, IntIntMap ends, int[] cursors, int rounds, int mode) {
		long best = Long.MAX_VALUE;
		int  mask = cursors.length - 1;
		for (int r = 0; r < 5; r++) {
			long t0  = System.nanoTime();
			int  acc = 0;
			for (int i = 0; i < rounds; i++) {
				int c = cursors[i & mask];
				acc += switch (mode) {
					case 0 -> binary(keys, ends, c);
					case 1 -> reverse(keys, ends, c);
					case 2 -> hybrid(keys, ends, c);
					default -> gallop(keys, ends, c);
				};
			}
			long dt = System.nanoTime() - t0;
			sink += acc;
			best = Math.min(best, dt);
		}
		return best;
	}

	static double avgProbes(IntSeq keys, IntIntMap ends, int[] cursors, boolean rev) {
		long sum = 0;
		for (int c : cursors) sum += probes(keys, ends, c, rev);
		return (double) sum / cursors.length;
	}

	public static void main(String[] args) {
		check();

		String[] names = {"均匀", "最右", "最左", "全空隙", "末1/10", "末1/4"};
		System.out.println();
		System.out.println("每格：ns/次（5 轮取最小），括号里是平均数组访问次数");
		System.out.printf("%-5s %-6s %14s %14s %14s%n", "块数", "分布", "二分", "倒序", "右端倍增");
		for (int k : new int[]{4, 8, 16, 32, 64, 256, 1024}) {
			IntSeq    keys = keysOf(k);
			IntIntMap ends = endsOf(k);
			int       rounds = Math.max(200_000, 40_000_000 / k);
			for (int kind = 0; kind < names.length; kind++) {
				int[] cs = cursors(kind, k, 1024);
				long  b  = bench(keys, ends, cs, rounds, 0);
				long  v  = bench(keys, ends, cs, rounds, 1);
				long  g  = bench(keys, ends, cs, rounds, 3);
				double nb = avgProbes(keys, ends, cs, false);
				double nv = avgProbes(keys, ends, cs, true);
				System.out.printf("%-5d %-6s %8.2f(%4.1f) %8.2f(%6.1f) %8.2f%n",
				                  k, names[kind],
				                  b / (double) rounds, nb,
				                  v / (double) rounds, nv,
				                  g / (double) rounds);
			}
		}
	}

	/** 三种实现必须逐点一致 */
	static void check() {
		for (int k : new int[]{1, 2, 3, 4, 7, 8, 15, 16, 100, 1000}) {
			IntSeq    keys = keysOf(k);
			IntIntMap ends = endsOf(k);
			for (int c = -5; c < k * 10 + 5; c++) {
				int a = binary(keys, ends, c);
				int b = reverse(keys, ends, c);
				int g = gallop(keys, ends, c);
				if (a != b || a != g) {
					System.out.printf("不一致：k=%d cursor=%d 二分=%d 倒序=%d 倍增=%d%n", k, c, a, b, g);
					return;
				}
			}
		}
		System.out.println("三种实现在 cursor ∈ [-5, 10k+5) 上结果完全一致（k = 1,2,3,4,7,8,15,16,100,1000）");
	}
}
