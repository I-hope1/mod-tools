package nipx.profiler;

import arc.Core;
import arc.graphics.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import nipx.HotSwapAgent;
import static nipx.HotSwapAgent.error;
import static nipx.HotSwapAgent.info;

/**
 * GPU 耗时 profiler，基于 OpenGL Timer Query（{@code GL_TIME_ELAPSED}）
 *
 * <h3>为什么需要这个</h3>
 * OpenGL 指令异步执行：{@code SpriteBatch.flush()} 只是把指令写入 GPU 命令队列，
 * GPU 实际执行时间全部累积到 {@code glSwapBuffers} 的等待中。
 * Timer Query 由 GPU 硬件填写，计量的是 GPU 真正执行该批次的时间。
 *
 * <h3>注意：GL_TIME_ELAPSED 精度</h3>
 * Arc 的 {@link GL30} 只提供 {@code glGetQueryObjectuiv}（32-bit unsigned），
 * 返回值单位是纳秒，uint32 最大约 4.29 秒，单次 flush 远不会溢出。
 * 如需更高精度可用扩展 {@code EXT_disjoint_timer_query} 的 64-bit 版本，
 * 但通常不必要。
 *
 * <h3>时序</h3>
 * <pre>
 * 帧 N：
 *   onFlushEnter() → glBeginQuery(GL_TIME_ELAPSED, queryId)
 *   ... GPU 指令提交 ...
 *   onFlushExit()  → glEndQuery(GL_TIME_ELAPSED)
 *
 * 帧 N+1 开始：
 *   drainResults() → 非阻塞检查 QUERY_RESULT_AVAILABLE
 *                  → 读取 QUERY_RESULT → 写入 gpuData / flameRoot
 * </pre>
 */
public class GlTimerProfiler {

	// ── 常量（从 GL30 接口读取，避免硬编码） ─────────────────────────────────
	private static final int GL_TIME_ELAPSED           = 0x88BF;  // ARB_timer_query / EXT_disjoint_timer_query
	private static final int GL_QUERY_RESULT_AVAILABLE = GL30.GL_QUERY_RESULT_AVAILABLE; // 0x8867
	private static final int GL_QUERY_RESULT           = GL30.GL_QUERY_RESULT;           // 0x8866

	// ── 配置 ─────────────────────────────────────────────────────────────────
	public static volatile boolean enabled = false;

	/** 环形缓冲大小：同时飞行中的 query 数量上限，每帧 flush 次数通常 < 64。*/
	private static final int RING = 128;

	// ── 环形缓冲 ─────────────────────────────────────────────────────────────
	private static int[]   queryIds  = null;   // glGenQueries 分配，一次性
	private static String[] queryKeys = new String[RING];

	private static int writeIdx = 0;
	private static int readIdx  = 0;

	/** 复用的 NIO buffer，避免每帧分配。只在 GL 线程访问，无并发问题。*/
	private static final IntBuffer tmpInt = ByteBuffer
		.allocateDirect(RING * 4)
		.order(ByteOrder.nativeOrder())
		.asIntBuffer();

	private static final IntBuffer oneInt = ByteBuffer
		.allocateDirect(4)
		.order(ByteOrder.nativeOrder())
		.asIntBuffer();

	// ── 输出数据 ─────────────────────────────────────────────────────────────
	/** flushKey → 累计 GPU 纳秒。供 FlameGraphWindow 渲染 GPU 泳道。*/
	public static final ConcurrentHashMap<String, LongAdder> gpuData = new ConcurrentHashMap<>();

	// ── 初始化 ────────────────────────────────────────────────────────────────

	private static boolean initialized = false;

	/** 懒初始化：在第一次 flush 时调用，此时 GL context 已就绪 */
	static boolean ensureInit() {
		if (initialized) return queryIds != null;
		initialized = true;
		try {
			GL30 gl = Core.gl30;
			if (gl == null) {
				info("[GlTimer] Core.gl30 is null, GPU profiling unavailable");
				return false;
			}
			tmpInt.clear();
			tmpInt.limit(RING);
			gl.glGenQueries(RING, tmpInt);
			queryIds = new int[RING];
			tmpInt.position(0);
			tmpInt.get(queryIds);
			info("[GlTimer] Initialized, " + RING + " query slots");
			return true;
		} catch (Throwable t) {
			HotSwapAgent.error("[GlTimer] Failed to initialize OpenGL timer queries", t);
			queryIds = null;
			return false;
		}
	}

	// ── 注入点（由 GlTimerInjector 插入到 SpriteBatch.flush()） ───────────────

	private static boolean isQuerying = false;
	/** flush 入口：开始 GPU 计时，记录当前方法 key 作为归属 */
	public static void onFlushEnter(String flushKey) {
		if (isQuerying || !enabled || !ensureInit()) return;
		if (writeIdx - readIdx >= RING) return;

		try {
			int slot = writeIdx & (RING - 1);
			queryKeys[slot] = flushKey;
			Core.gl30.glBeginQuery(GL_TIME_ELAPSED, queryIds[slot]);
			isQuerying = true;
		} catch (Throwable t) {
			isQuerying = false;
			enabled = false;
			HotSwapAgent.error("[GlTimer] glBeginQuery failed, disabling GPU profiler", t);
		}
	}

	/** flush 出口：结束 GPU 计时，推进写指针 */
	public static void onFlushExit() {
		if (!isQuerying || !enabled || queryIds == null) return;
		try {
			Core.gl30.glEndQuery(GL_TIME_ELAPSED);
			writeIdx++;
		} catch (Throwable t) {
			enabled = false;
			HotSwapAgent.error("[GlTimer] glEndQuery failed, disabling GPU profiler", t);
		} finally {
			isQuerying = false;
		}
	}

	// ── 结果读取（由 GlTimerInjector 注入到帧循环入口） ──────────────────────

	/** 非阻塞读取已完成的 GPU query 结果并写入火焰图树 */
	public static void drainResults() {
		if (queryIds == null || !enabled) return;
		GL30 gl = Core.gl30;
		if (gl == null) return;

		try {
			while (readIdx < writeIdx) {
				int slot = readIdx & (RING - 1);

				// 非阻塞检查：GPU 还没完成则停止（后续 query 更不可能完成）
				oneInt.clear();
				gl.glGetQueryObjectuiv(queryIds[slot], GL_QUERY_RESULT_AVAILABLE, oneInt);
				if (oneInt.get(0) == 0) break;

				// 读取结果（uint32，单位纳秒，~4.29s 才溢出，每帧 flush 安全）
				oneInt.clear();
				gl.glGetQueryObjectuiv(queryIds[slot], GL_QUERY_RESULT, oneInt);
				long gpuNs = Integer.toUnsignedLong(oneInt.get(0));

				String key = queryKeys[slot];
				if (key != null) {
					// 写入 flat GPU 统计
					gpuData.computeIfAbsent(key, k -> new LongAdder()).add(gpuNs);

					// 写入火焰图树（与 CPU 节点并列，节点名加 "[gpu]" 后缀）
					String gpuKey = key.isBlank() ? "[gpu]" : key + "[gpu]";
					ProfilerData.FlameNode gpuNode =
						ProfilerData.flameRoot.getOrCreateChild(gpuKey);
					gpuNode.totalNanos.add(gpuNs);

					queryKeys[slot] = null;
				}
				readIdx++;
			}
		} catch (Throwable t) {
			enabled = false;
			HotSwapAgent.error("[GlTimer] drainResults failed, disabling GPU profiler", t);
		}
	}

	// ── 清理 ─────────────────────────────────────────────────────────────────

	public static void clear() {
		gpuData.clear();
		writeIdx = 0;
		readIdx  = 0;
		Arrays.fill(queryKeys, null);
	}

	/**
	 * 释放 GL query 对象（窗口关闭 / 功能禁用时调用）。
	 * 必须在 GL 线程上调用。
	 */
	public static void dispose() {
		if (queryIds == null) return;
		tmpInt.clear();
		tmpInt.limit(RING);
		for (int id : queryIds) tmpInt.put(id);
		tmpInt.flip();
		Core.gl30.glDeleteQueries(RING, tmpInt);
		queryIds    = null;
		initialized = false;
		info("[GlTimer] Disposed");
	}
}