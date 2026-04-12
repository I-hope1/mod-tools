package nipx.jvmti;

import nipx.jni.JNIEnv;
import nipx.jni.helper.GlobalRef;
import nipx.jvmti.JVMTIEnv.FrameConsumer;

import java.lang.foreign.MemorySegment;
import java.util.List;

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
			return JVMTIEnv.getInstance()
			 .captureThreadLocals(jniEnv, thread, 32, 0); // 远程线程不需要跳过本地帧
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
			JVMTIEnv.getInstance().walkCurrentThreadFrames(jniEnv, threadHandle, 64, thread == Thread.currentThread() ? 0 : 3, consumer);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// -------------------------------------------------------------------------
	// Convenience: pretty-print to stdout
	// -------------------------------------------------------------------------

	/** Prints a formatted snapshot of the current thread's local variables. */
	public static void dump(JNIEnv jniEnv) {
		List<FrameLocals> frames = captureCurrent(jniEnv, 32, 3); // +1 for dump() itself
		System.out.println("=== Stack local-variable snapshot ===");
		for (FrameLocals f : frames) {
			System.out.print(f);
		}
		System.out.println("=====================================");
	}
}