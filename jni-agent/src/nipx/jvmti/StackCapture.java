package nipx.jvmti;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector.Argument;
import nipx.jni.JNIEnv;
import nipx.jni.helper.GlobalRef;
import nipx.jvmti.JVMTIEnv.FrameConsumer;

import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.Supplier;

/**
 * Convenient entry point for capturing stack-frame local variables.
 *
 * <pre>{@code
 * // Typical one-liner (skip our own frame + StackCapture.capture frame = 2)
 * try (Arena a = Arena.ofConfined()) {
 *     JNIEnv jni = new JNIEnv(a);
 *     List<FrameLocals> frames = StackCapture.capture(jni);
 *     frames.forEach(System.out::println);
 * }
 * }</pre>
 *
 * <h3>Requirements</h3>
 * <ul>
 *   <li>JDK 22+ (Panama FFI + JVMTI)</li>
 *   <li>Classes compiled with {@code -g} for local-variable debug info.
 *       Frames without debug info produce an empty {@code locals} list.</li>
 *   <li>JVM flag: {@code --enable-native-access=ALL-UNNAMED} (or the module
 *       that owns this code).</li>
 * </ul>
 */
public final class StackCapture {

	private StackCapture() { }

	public static List<FrameLocals> capture(JNIEnv jniEnv, Thread thread) {
		return withSuspend(thread, () ->
		 JVMTIEnv.getInstance().captureThreadLocals(jniEnv, thread, 32, 0));
	}

	/**
	 * Captures up to 32 frames, automatically skipping the two infrastructure
	 * frames ({@code JVMTIEnv.captureCurrentThreadLocals} and this method).
	 */
	public static List<FrameLocals> captureCurrent(JNIEnv jniEnv) {
		return captureCurrent(jniEnv, 32, 2);
	}

	/**
	 * Captures up to {@code maxDepth} frames, skipping {@code skipFrames} from
	 * the top.
	 *
	 * <p>The default skip value of {@code 2} hides:
	 * <ol>
	 *   <li>{@code JVMTIEnv.captureCurrentThreadLocals} (depth 0)</li>
	 *   <li>{@code StackCapture.capture} itself (depth 1)</li>
	 * </ol>
	 * so depth 0 of the returned list is your immediate caller.
	 * @param jniEnv     an active {@link JNIEnv} (must remain open for the
	 *                   duration of this call)
	 * @param maxDepth   maximum frames to return
	 * @param skipFrames infrastructure frames to hide (≥ 2 recommended)
	 */
	public static List<FrameLocals> captureCurrent(JNIEnv jniEnv, int maxDepth, int skipFrames) {
		return JVMTIEnv.getInstance()
		 .captureCurrentThreadLocals(jniEnv, maxDepth, skipFrames);
	}
	public static void captureInto(JNIEnv jniEnv, Thread thread, FrameConsumer consumer) {
		try (GlobalRef ref = jniEnv.JavaObjectToJObject(thread)) {
			MemorySegment threadHandle = ref.ref();
			withSuspend(thread, () -> {
				JVMTIEnv.getInstance().walkCurrentThreadFrames(jniEnv, threadHandle, 64, thread == Thread.currentThread() ? 0 : 3, consumer);
				return null;
			});
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	//region dump: pretty-print to stdout

	/** Prints a formatted snapshot of the current thread's local variables. */
	public static void dump(JNIEnv jniEnv) {
		List<FrameLocals> frames = captureCurrent(jniEnv, 32, 3); // +1 for dump() itself
		System.out.println("=== Stack local-variable snapshot ===");
		for (FrameLocals f : frames) {
			System.out.print(f);
		}
		System.out.println("=====================================");
	}
	//endregion


	//region utils

	private static Integer findJdwpPort() {
		List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
		for (String arg : args) {
			if (arg.contains("jdwp") && arg.contains("address=")) {
				try {
					String address = arg.substring(arg.indexOf("address=") + 8);
					if (address.contains(",")) address = address.split(",")[0];
					if (address.contains(":")) address = address.split(":")[1];
					return Integer.parseInt(address);
				} catch (Exception ignored) { }
			}
		}
		return null;
	}

	private static VirtualMachine attachLocal(int port) throws Exception {
		AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
		 .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
		 .findFirst().orElseThrow();
		Map<String, Argument> args = connector.defaultArguments();
		args.get("port").setValue(String.valueOf(port));
		args.get("hostname").setValue("127.0.0.1");
		return connector.attach(args);
	}

	private static volatile VirtualMachine cachedVM  = null;
	private static final    LongLongMap    threadIDs = new LongLongMap(16);
	private static synchronized VirtualMachine getVM(int port) throws Exception {
		if (cachedVM == null) {
			cachedVM = attachLocal(port);
		}
		return cachedVM;
	}

	private static <T> T withSuspend(Thread thread, Supplier<T> r) {
		if (thread == Thread.currentThread()) {
			return r.get();
		}
		Integer port = findJdwpPort();
		if (port == null) {
			return r.get();
		}
		try {
			VirtualMachine        vm   = getVM(port);
			List<ThreadReference> list = vm.allThreads();
			for (ThreadReference tr : list) {
				if (threadID(tr) != thread.threadId()) continue;

				tr.suspend(); // 借助 JDWP 的力量挂起
				try {
					return r.get();
				} finally {
					tr.resume();
				}
			}
		} catch (Throwable e) {
			// System.err.println("[StackCapture] JDI bridge failed: " + e.getMessage());
			// 回退到原生尝试
			return r.get();
		}
		// unreachable
		return null;
	}
	private static long threadID(ThreadReference threadRef) {
		if (threadIDs.containsKey(threadRef.uniqueID())) {
			return threadIDs.get(threadRef.uniqueID());
		}
		ReferenceType threadType = threadRef.referenceType();

		Field tidField = threadType.fieldByName("tid");
		long  tid      = -1;
		if (tidField != null) {
			LongValue jdiTid = (LongValue) threadRef.getValue(tidField);
			tid = jdiTid.value();
		}
		threadIDs.put(threadRef.uniqueID(), tid);
		return tid;
	}
	//endregion


	/**
	 * <p>专门为 long->long 映射设计的轻量级 Map。</p>
	 * <p>内存占用极小，拒绝包装类垃圾。</p>
	 * <p>PS：返回值 {@value NOT_FOUND} 是一个特殊值，表示无值。</p>
	 */
	public static class LongLongMap {
		public static final long EMPTY_KEY = 0;
		public static final long NOT_FOUND = Long.MIN_VALUE;

		private       long[] keys;
		private       long[] values;
		/** size 不包含 zero-key */
		private       int    size;
		private       int    capacity;
		private final float  loadFactor = 0.75f;

		private boolean hasZero;
		private long    zeroValue;

		public LongLongMap(int initialCapacity) {
			this.capacity = powerOfTwo(initialCapacity);
			this.keys = new long[capacity];
			this.values = new long[capacity];
		}

		public void put(long key, long value) {
			if (value == NOT_FOUND) throw new IllegalArgumentException("value == " + NOT_FOUND);
			if (key == EMPTY_KEY) {
				hasZero = true;
				zeroValue = value;
				return;
			}

			if (size >= capacity * loadFactor) rehash();

			int idx = hash(key) & (capacity - 1);
			while (keys[idx] != EMPTY_KEY) {
				if (keys[idx] == key) {
					values[idx] = value;
					return;
				}
				idx = (idx + 1) & (capacity - 1);
			}
			keys[idx] = key;
			values[idx] = value;
			size++;
		}

		/** 返回值 {@link #NOT_FOUND} {@value #NOT_FOUND} 是一个特殊值，表示无值。 */
		public long get(long key) {
			if (key == EMPTY_KEY) {
				if (hasZero) return zeroValue;
				return NOT_FOUND;
			}
			int idx = hash(key) & (capacity - 1);
			while (keys[idx] != EMPTY_KEY) {
				if (keys[idx] == key) return values[idx];
				idx = (idx + 1) & (capacity - 1);
			}
			return NOT_FOUND;
		}
		public boolean containsKey(long key) {
			if (key == EMPTY_KEY) {
				return hasZero;
			}
			int idx = hash(key) & (capacity - 1);
			while (keys[idx] != EMPTY_KEY) {
				if (keys[idx] == key) return true;
				idx = (idx + 1) & (capacity - 1);
			}
			return false;
		}

		public void clear() {
			Arrays.fill(keys, EMPTY_KEY);
			Arrays.fill(values, EMPTY_KEY);

			hasZero = false;
			zeroValue = 0;
			size = 0;
		}

		public int size() { return size + (hasZero ? 1 : 0); }
		public boolean isEmpty() { return size() == 0; }

		private void rehash() {
			if (capacity > (1 << 30)) throw new OutOfMemoryError("Capacity overflow");

			long[] oldKeys   = keys;
			long[] oldValues = values;
			capacity <<= 1;
			keys = new long[capacity];
			values = new long[capacity];
			size = 0;
			for (int i = 0; i < oldKeys.length; i++) {
				if (oldKeys[i] != EMPTY_KEY) put(oldKeys[i], oldValues[i]);
			}
		}

		private int hash(long v) {
			v ^= (v >>> 33);
			v *= 0xff51afd7ed558ccdL;
			v ^= (v >>> 33);
			v *= 0xc4ceb9fe1a85ec53L; // 额外常量混高位
			v ^= (v >>> 33);
			return (int) v;
		}

		private int powerOfTwo(int n) {
			int res = 1;
			while (res < n) res <<= 1;
			return res;
		}
	}

}