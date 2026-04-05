package nipx.jvmti;

import nipx.jni.JNIEnv;
import nipx.jni.helper.*;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Pure-Panama wrapper around a {@code jvmtiEnv*}.
 *
 * <h3>Depth consistency guarantee</h3>
 * JVMTI depths are measured from the <em>current</em> top of the stack at the
 * moment of each call.  This implementation therefore performs all
 * {@code GetLocal*} reads <strong>inline</strong> inside
 * {@link #captureCurrentThreadLocals} — never from a nested helper — so the
 * depth values obtained by {@code GetStackTrace} remain valid when passed to
 * {@code GetLocal*} later in the same method body.
 */
public class JVMTIEnv {

	// region Constants

	private static final long ADDR_SIZE = ValueLayout.ADDRESS.byteSize();

	public static final int JVMTI_VERSION_1_2 = 0x30010200;

	public static final int JVMTI_ERROR_NONE         = 0;
	public static final int JVMTI_ERROR_OPAQUE_FRAME = 31;
	public static final int JVMTI_ERROR_INVALID_SLOT = 35;
	public static final int JVMTI_ERROR_ABSENT_INFO  = 101;
	public static final int JNI_OK                   = 0;

	/** @see <a href=https://pages.cs.wisc.edu/~starr/bots/EISBot-src/html/structjvmtiCapabilities.html>REF</a> */
	private static final int  CAN_GET_THREAD_STATE       = 1 << 2;
	/**
	 * jvmtiCapabilities.can_access_local_variables is bit 14 of the first jint
	 * in the 128-byte bitfield struct.
	 */
	private static final int  CAN_ACCESS_LOCAL_VARIABLES = 1 << 14;
	private static final int  CAN_SUSPEND                = 1 << 19;
	private static final long JVMTICAPS_SIZE             = 128L;

	// -------------------------------------------------------------------------
	// JVMTI function-table slot indices  (0-based, i.e. spec slot N → index N-1)
	// Source: jvmti.h from OpenJDK 21+ (includes GetAllModules at slot 3)
	// -------------------------------------------------------------------------
	private static final long IDX_GetFrameCount           = 15;  // slot 16
	private static final long IDX_GetLocalObject          = 20;  // slot 21
	private static final long IDX_GetLocalInt             = 21;  // slot 22
	private static final long IDX_GetLocalLong            = 22;  // slot 23
	private static final long IDX_GetLocalFloat           = 23;  // slot 24
	private static final long IDX_GetLocalDouble          = 24;  // slot 25
	private static final long IDX_Deallocate              = 46;  // slot 47
	private static final long IDX_GetClassSignature       = 47;  // slot 48
	private static final long IDX_GetMethodName           = 63;  // slot 64
	private static final long IDX_GetMethodDeclaringClass = 64;  // slot 65
	private static final long IDX_GetLocalVariableTable   = 71;  // slot 72
	private static final long IDX_SuspendThread           = 4;  // slot 5
	private static final long IDX_ResumeThread            = 5;  // slot 6
	private static final long IDX_GetStackTrace           = 103; // slot 104
	private static final long IDX_GetThreadState          = 108; // slot 109
	private static final long IDX_AddCapabilities         = 141; // slot 142

	/** JNIInvokeInterface_ index for GetEnv (0-based). */
	private static final long IDX_JavaVM_GetEnv = 6;

	//endregion
	//region jvmtiFrameInfo  { jmethodID(8) | jlocation/jlong(8) }  = 16 bytes
	private static final long FRAME_SIZE         = 16L;
	private static final long FRAME_METHOD_OFF   = 0L;
	private static final long FRAME_LOCATION_OFF = 8L;

	// -------------------------------------------------------------------------
	// jvmtiLocalVariableEntry layout (64-bit, with natural padding):
	//   0  : jlocation start_location  (8)
	//   8  : jint length               (4)
	//   12 : padding                   (4)
	//   16 : char* name                (8)
	//   24 : char* signature           (8)
	//   32 : char* generic_signature   (8)
	//   40 : jint slot                 (4)
	//   44 : padding                   (4)
	//   total: 48 bytes
	// -------------------------------------------------------------------------
	private static final long LVE_SIZE      = 48L;
	private static final long LVE_START_LOC = 0L;
	private static final long LVE_LENGTH    = 8L;
	private static final long LVE_NAME      = 16L;
	private static final long LVE_SIGNATURE = 24L;
	private static final long LVE_GENERIC   = 32L;
	private static final long LVE_SLOT      = 40L;

	// -------------------------------------------------------------------------
	// Unbound MethodHandles (function pointer is the FIRST invoke argument)
	// Pattern mirrors JNIEnvFunctions exactly.
	// -------------------------------------------------------------------------

	/** JavaVM::GetEnv(JavaVM*, void**, jint) → jint */
	private static final MethodHandle MH_JavaVM_GetEnv = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // JavaVM*
		ValueLayout.ADDRESS,  // void** (output jvmtiEnv*)
		ValueLayout.JAVA_INT  // version
	 ));

	/** AddCapabilities(env, caps*) → jint */
	private static final MethodHandle MH_AddCapabilities = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS   // const jvmtiCapabilities*
	 ));
	/** SuspendThread(env, thread) → jint */
	private static final MethodHandle MH_SuspendThread   = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS, // jvmtiEnv*
		ValueLayout.ADDRESS // jthread
	 ));
	/** ResumeThread(env, thread) → jint */
	private static final MethodHandle MH_ResumeThread    = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS, // jvmtiEnv*
		ValueLayout.ADDRESS // jthread
	 ));
	/** GetThreadState(env, thread, thread_state*) → jint */
	private static final MethodHandle MH_GetThreadState  = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jthread
		ValueLayout.ADDRESS   // jint* thread_state_ptr
	 ));

	/** GetStackTrace(env, thread, start_depth, max_frames, frame_buf*, count*) → jint */
	private static final MethodHandle MH_GetStackTrace = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jthread  (NULL = current thread)
		ValueLayout.JAVA_INT, // start_depth
		ValueLayout.JAVA_INT, // max_frame_count
		ValueLayout.ADDRESS,  // jvmtiFrameInfo* frame_buffer
		ValueLayout.ADDRESS   // jint* count_ptr
	 ));

	/** GetLocalVariableTable(env, methodID, count*, table**) → jint */
	private static final MethodHandle MH_GetLocalVariableTable = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jmethodID
		ValueLayout.ADDRESS,  // jint* entry_count_ptr
		ValueLayout.ADDRESS   // jvmtiLocalVariableEntry** table_ptr
	 ));

	/** GetLocalObject(env, thread, depth, slot, jobject*) → jint */
	private static final MethodHandle MH_GetLocalObject = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jthread
		ValueLayout.JAVA_INT, // depth
		ValueLayout.JAVA_INT, // slot
		ValueLayout.ADDRESS   // jobject* out
	 ));

	/** GetLocalInt / GetLocalLong / GetLocalFloat / GetLocalDouble share this shape. */
	private static final MethodHandle MH_GetLocalPrimitive = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jthread
		ValueLayout.JAVA_INT, // depth
		ValueLayout.JAVA_INT, // slot
		ValueLayout.ADDRESS   // T* out
	 ));

	/** GetMethodName(env, methodID, char** name, char** sig, char** generic) → jint */
	private static final MethodHandle MH_GetMethodName = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jmethodID
		ValueLayout.ADDRESS,  // char** name_ptr
		ValueLayout.ADDRESS,  // char** signature_ptr
		ValueLayout.ADDRESS   // char** generic_ptr (we pass NULL)
	 ));

	/** GetMethodDeclaringClass(env, methodID, jclass*) → jint */
	private static final MethodHandle MH_GetMethodDeclaringClass = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jmethodID
		ValueLayout.ADDRESS   // jclass* declaring_class_ptr
	 ));

	/** GetClassSignature(env, jclass, char** sig, char** generic) → jint */
	private static final MethodHandle MH_GetClassSignature = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jclass
		ValueLayout.ADDRESS,  // char** signature_ptr
		ValueLayout.ADDRESS   // char** generic_ptr (we pass NULL)
	 ));

	/** Deallocate(env, mem*) → jint */
	private static final MethodHandle MH_Deallocate = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS   // void* mem
	 ));

	//endregion
	//region Instance state

	private final MemorySegment jvmtiEnvPtr; // jvmtiEnv*  (struct, first word = fn-table*)
	private final MemorySegment fnTable;     // jvmtiInterface_1_*

	// Cached function pointers
	private final MemorySegment fpGetStackTrace;
	private final MemorySegment fpGetLocalVariableTable;
	private final MemorySegment fpGetLocalObject;
	private final MemorySegment fpGetLocalInt;
	private final MemorySegment fpGetLocalLong;
	private final MemorySegment fpGetLocalFloat;
	private final MemorySegment fpGetLocalDouble;
	private final MemorySegment fpGetMethodName;
	private final MemorySegment fpGetMethodDeclaringClass;
	private final MemorySegment fpGetClassSignature;
	private final MemorySegment fpDeallocate;
	private final MemorySegment fpSuspendThread;
	private final MemorySegment fpResumeThread;
	private final MemorySegment fpGetThreadState;

	//endregion
	//region Singleton

	private static volatile JVMTIEnv INSTANCE;

	/** Returns the process-wide singleton, creating it on first call. */
	public static JVMTIEnv getInstance() {
		if (INSTANCE == null) {
			synchronized (JVMTIEnv.class) {
				if (INSTANCE == null) {
					INSTANCE = new JVMTIEnv();
				}
			}
		}
		return INSTANCE;
	}

	//endregion
	//region Constructor

	private JVMTIEnv() {
		this.jvmtiEnvPtr = acquireJvmtiEnv();
		// jvmtiEnv* → first word is the function-table pointer (same layout as JNIEnv)
		this.fnTable = jvmtiEnvPtr.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

		fpGetStackTrace = fp(IDX_GetStackTrace);
		fpGetLocalVariableTable = fp(IDX_GetLocalVariableTable);
		fpGetLocalObject = fp(IDX_GetLocalObject);
		fpGetLocalInt = fp(IDX_GetLocalInt);
		fpGetLocalLong = fp(IDX_GetLocalLong);
		fpGetLocalFloat = fp(IDX_GetLocalFloat);
		fpGetLocalDouble = fp(IDX_GetLocalDouble);
		fpGetMethodName = fp(IDX_GetMethodName);
		fpGetMethodDeclaringClass = fp(IDX_GetMethodDeclaringClass);
		fpGetClassSignature = fp(IDX_GetClassSignature);
		fpDeallocate = fp(IDX_Deallocate);
		fpSuspendThread = fp(IDX_SuspendThread);
		fpResumeThread = fp(IDX_ResumeThread);
		fpGetThreadState = fp(IDX_GetThreadState);

		enableRequiredCapabilities();
	}

	private MemorySegment fp(long index) {
		return fnTable.get(ValueLayout.ADDRESS, index * ADDR_SIZE);
	}

	//endregion
	//region Initialization helpers

	/**
	 * Calls {@code JavaVM::GetEnv(JVMTI_VERSION_1_2)} using the same
	 * {@code MAIN_VM_Pointer} that {@link JNIEnv} already owns (accessed via
	 * {@link MasterKey}).
	 */
	private static MemorySegment acquireJvmtiEnv() {
		try {
			// Grab the private static MAIN_VM_Pointer from JNIEnv
			Field         vmField = JNIEnv.class.getDeclaredField("MAIN_VM_Pointer");
			VarHandle     vh      = MasterKey.INSTANCE.openTheDoor(vmField);
			MemorySegment vmPtr   = (MemorySegment) vh.get(); // JavaVM*

			// Dereference JavaVM* → function-table pointer (JNIInvokeInterface_*)
			MemorySegment vmFnTable = vmPtr.reinterpret(Long.MAX_VALUE)
			 .get(ValueLayout.ADDRESS, 0)
			 .reinterpret(Long.MAX_VALUE);
			MemorySegment getEnvFp = vmFnTable.get(ValueLayout.ADDRESS,
			 IDX_JavaVM_GetEnv * ADDR_SIZE);

			try (Arena tmp = Arena.ofConfined()) {
				MemorySegment envOut = tmp.allocate(ValueLayout.ADDRESS);
				int rc = (int) MH_JavaVM_GetEnv.invokeExact(getEnvFp, vmPtr, envOut,
				 JVMTI_VERSION_1_2);
				if (rc != JNI_OK) {
					throw new RuntimeException(
					 "JavaVM::GetEnv(JVMTI_VERSION_1_2) failed, rc=" + rc);
				}
				// Copy address out of the confined arena so it survives closure
				return envOut.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
			}
		} catch (Throwable t) {
			throw new RuntimeException("Failed to acquire jvmtiEnv*", t);
		}
	}

	/** 包括can_suspend，can_access_local_variables */
	private void enableRequiredCapabilities() {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment caps = tmp.allocate(JVMTICAPS_SIZE, 8);
			// jvmtiCapabilities 位定义 (first jint, offset 0):
			caps.set(ValueLayout.JAVA_INT, 0, CAN_ACCESS_LOCAL_VARIABLES);
			int rc = (int) MH_AddCapabilities.invokeExact(
			 fp(IDX_AddCapabilities), jvmtiEnvPtr, caps);
			if (rc != JVMTI_ERROR_NONE) {
				// 仅仅记录警告，不要 throw
				System.err.println("[W] JVMTI: Local variable access not available in this phase (rc=" + rc + ")");
			}
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}
	private boolean canSafelySuspend(MemorySegment thread) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment stateOut = tmp.allocate(ValueLayout.JAVA_INT);
			int rc = (int) MH_GetThreadState.invokeExact(
			 fpGetThreadState, jvmtiEnvPtr, thread, stateOut);
			if (rc != JVMTI_ERROR_NONE) return false;

			int state = stateOut.get(ValueLayout.JAVA_INT, 0);

			// 不能挂起的状态
			if ((state & 0x2) != 0) return false;  // JVMTI_THREAD_STATE_TERMINATED
			if ((state & 0x10) != 0) return false; // JVMTI_THREAD_STATE_SUSPENDED (已挂起)
			if ((state & 0x200) != 0) return false;// JVMTI_THREAD_STATE_IN_NATIVE (native 中，风险高)

			// 谨慎挂起的状态（可选）
			// if ((state & 0x40) != 0) return false; // JVMTI_THREAD_STATE_WAITING

			return true;
		} catch (Throwable e) {
			e.printStackTrace();
			return false; // 保守策略：检查失败就不挂起
		}
	}

	//endregion
	//region Public API

	/**
	 * Captures local variables for every frame of the <em>current</em> thread.
	 *
	 * <p><b>Depth note:</b> all {@code GetLocal*} calls are made directly
	 * inside this method so that depth values from {@code GetStackTrace} remain
	 * valid.  Do <em>not</em> move those calls into sub-methods.
	 * @param maxDepth   maximum number of frames to inspect (e.g. 32)
	 * @param skipFrames frames to drop from the top of the trace.
	 *                   Pass {@code 1} to hide this method itself;
	 *                   pass {@code 2} when called through {@link StackCapture}.
	 * @return immutable list of {@link FrameLocals}, shallowest first
	 */
	public List<FrameLocals> captureCurrentThreadLocals(JNIEnv jniEnv, int maxDepth, int skipFrames) {
		return captureThreadLocals(jniEnv, MemorySegment.NULL, maxDepth, skipFrames);
	}
	public List<FrameLocals> captureThreadLocals(JNIEnv jniEnv, MemorySegment targetThread, int maxDepth,
	                                             int skipFrames) {
		boolean isCurrent = targetThread.equals(MemorySegment.NULL) || targetThread.address() == 0;
		boolean suspended = false;


		// 如果不是当前线程，必须挂起
		/* if (!isCurrent) {
			try {
				// System.out.println(jniEnv.jObjectToJavaObject(targetThread)); // 没问题
				int rc = (int) MH_SuspendThread.invokeExact(fpSuspendThread, jvmtiEnvPtr, targetThread);
				if (rc == 15 || rc == 99) {
					return Collections.emptyList(); // 线程已消失，直接返回空结果，不报错
				}
				if (rc != JVMTI_ERROR_NONE) {
					// 其他错误（如权限问题）再抛出
					checkError(rc, "SuspendThread");
				}
				suspended = true;
			} catch (Throwable t) { throw new RuntimeException(t); }
		} */
		try (Arena arena = Arena.ofConfined()) {

			// ------------------------------------------------------------------
			// 1. GetStackTrace  (NULL thread → current thread)
			// ------------------------------------------------------------------
			int           total    = maxDepth + skipFrames;
			MemorySegment frameBuf = arena.allocate(FRAME_SIZE * total, 8);
			MemorySegment cntOut   = arena.allocate(ValueLayout.JAVA_INT);

			int rc = (int) MH_GetStackTrace.invokeExact(
			 fpGetStackTrace, jvmtiEnvPtr,
			 targetThread,
			 0, total,
			 frameBuf, cntOut);
			checkError(rc, "GetStackTrace");

			int frameCount = cntOut.get(ValueLayout.JAVA_INT, 0);

			// ------------------------------------------------------------------
			// 2. Collect method metadata & variable tables (helper calls are fine
			//    here because we have not started calling GetLocal* yet).
			// ------------------------------------------------------------------
			MethodMeta[] metas  = new MethodMeta[frameCount];
			VarEntry[][] tables = new VarEntry[frameCount][];
			long[]       locs   = new long[frameCount];

			for (int d = 0; d < frameCount; d++) {
				long          off = d * FRAME_SIZE;
				MemorySegment mid = frameBuf.get(ValueLayout.ADDRESS, off + FRAME_METHOD_OFF);
				long          loc = frameBuf.get(ValueLayout.JAVA_LONG, off + FRAME_LOCATION_OFF);
				locs[d] = loc;
				metas[d] = fetchMethodMeta(arena, mid);

				// Skip GetLocalVariableTable for frames we are about to discard
				if (d < skipFrames) {
					tables[d] = VarEntry.EMPTY;
					continue;
				}
				tables[d] = fetchVarTable(arena, mid);
			}

			// ------------------------------------------------------------------
			// 3. Read local values.
			//
			//    *** ALL GetLocal* CALLS MUST STAY IN THIS METHOD ***
			//    Moving them into a helper shifts the depth baseline and makes
			//    slot reads target the wrong frame.
			// ------------------------------------------------------------------
			List<FrameLocals> result = new ArrayList<>(frameCount - skipFrames);

			for (int d = skipFrames; d < frameCount; d++) {
				VarEntry[]          vars   = tables[d];
				long                loc    = locs[d];
				List<LocalVariable> locals = new ArrayList<>(vars.length);

				for (VarEntry v : vars) {
					// Filter to variables live at this bytecode offset
					if (loc >= 0 && (loc < v.startLoc || loc >= v.startLoc + v.length)) {
						continue;
					}

					Object value = null;
					char   kind  = v.sig.isEmpty() ? 0 : v.sig.charAt(0);

					try {
						switch (kind) {
							// ---- object / array --------------------------------
							case 'L', '[' -> {
								MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
								int err = (int) MH_GetLocalObject.invokeExact(
								 fpGetLocalObject, jvmtiEnvPtr,
								 targetThread, d, v.slot, out);

								if (err == JVMTI_ERROR_NONE) {
									MemorySegment ref = out.get(ValueLayout.ADDRESS, 0);
									if (ref.address() != 0L) {
										// 被动获取信息：不调用 Java 方法，只调用 JVMTI/JNI 基础函数
										MemorySegment jclass = jniEnv.GetObjectClass(ref);
										String        sig    = fetchClassSig(arena, jclass);

										// 仅仅记录描述信息，不进行对象转换
										// 这样就避免了触发 Class.forName 或任何 Java 代码执行
										value = String.format("[%s @ 0x%x]", sig, ref.address());
									} else {
										value = "null";
									}
								}
							}
							// ---- boolean, byte, char, short, int ---------------
							case 'Z', 'B', 'C', 'S', 'I' -> {
								MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
								int err = (int) MH_GetLocalPrimitive.invokeExact(
								 fpGetLocalInt, jvmtiEnvPtr,
								 targetThread, d, v.slot, out);
								if (err == JVMTI_ERROR_NONE) {
									int raw = out.get(ValueLayout.JAVA_INT, 0);
									value = switch (kind) {
										case 'Z' -> raw != 0;
										case 'B' -> (byte) raw;
										case 'C' -> (char) raw;
										case 'S' -> (short) raw;
										default -> raw;
									};
								}
							}
							// ---- long ------------------------------------------
							case 'J' -> {
								MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
								int err = (int) MH_GetLocalPrimitive.invokeExact(
								 fpGetLocalLong, jvmtiEnvPtr,
								 targetThread, d, v.slot, out);
								if (err == JVMTI_ERROR_NONE) { value = out.get(ValueLayout.JAVA_LONG, 0); }
							}
							// ---- float -----------------------------------------
							case 'F' -> {
								MemorySegment out = arena.allocate(ValueLayout.JAVA_FLOAT);
								int err = (int) MH_GetLocalPrimitive.invokeExact(
								 fpGetLocalFloat, jvmtiEnvPtr,
								 targetThread, d, v.slot, out);
								if (err == JVMTI_ERROR_NONE) { value = out.get(ValueLayout.JAVA_FLOAT, 0); }
							}
							// ---- double ----------------------------------------
							case 'D' -> {
								MemorySegment out = arena.allocate(ValueLayout.JAVA_DOUBLE);
								int err = (int) MH_GetLocalPrimitive.invokeExact(
								 fpGetLocalDouble, jvmtiEnvPtr,
								 targetThread, d, v.slot, out);
								if (err == JVMTI_ERROR_NONE) { value = out.get(ValueLayout.JAVA_DOUBLE, 0); }
							}
							default -> { /* void / unknown — leave null */ }
						}
					} catch (Throwable ignored) {
						// Any GetLocal* failure → leave value as null
					}

					locals.add(new LocalVariable(v.name, v.sig, v.slot, value));
				}

				result.add(new FrameLocals(
				 metas[d].className, metas[d].methodName, metas[d].methodSig,
				 d, loc, Collections.unmodifiableList(locals)));
			}

			return Collections.unmodifiableList(result);

		} catch (Throwable t) {
			throw new RuntimeException("captureCurrentThreadLocals failed", t);
		} /* finally {
			// 无论成功失败，只要挂起了，就必须恢复
			if (!isCurrent && suspended) {
				try {
					MH_ResumeThread.invokeExact(fpResumeThread, jvmtiEnvPtr, targetThread);
				} catch (Throwable ignored) { }
			}
		} */
	}
	private int suspendThread(MemorySegment thread) throws Throwable {
		long deadline = System.currentTimeMillis() + 1000;

		while (System.currentTimeMillis() < deadline) {
			if (!canSafelySuspend(thread)) {
				Thread.sleep(5); // 短暂等待后重试
				continue;
			}

			int rc = (int) MH_SuspendThread.invokeExact(
			 fpSuspendThread, jvmtiEnvPtr, thread);

			if (rc == JVMTI_ERROR_NONE) return rc;
			if (rc == 15) return rc; // THREAD_NOT_ALIVE - 线程已消失
			if (rc == 99) return rc; // 能力不足 - 不应重试

			// 其他错误：短暂等待后重试
			Thread.sleep(2);
		}
		return 99; // 超时
	}

	//endregion
	//region Metadata helpers  (called before any GetLocal* — depth shift is harmless)

	private MethodMeta fetchMethodMeta(Arena arena, MemorySegment methodId) {
		String className  = "<unknown>";
		String methodName = "<unknown>";
		String methodSig  = "";
		try {
			// -- GetMethodName --
			MemorySegment namePtrOut = arena.allocate(ValueLayout.ADDRESS);
			MemorySegment sigPtrOut  = arena.allocate(ValueLayout.ADDRESS);
			int rc = (int) MH_GetMethodName.invokeExact(
			 fpGetMethodName, jvmtiEnvPtr, methodId,
			 namePtrOut, sigPtrOut, MemorySegment.NULL);
			if (rc == JVMTI_ERROR_NONE) {
				MemorySegment np = namePtrOut.get(ValueLayout.ADDRESS, 0);
				MemorySegment sp = sigPtrOut.get(ValueLayout.ADDRESS, 0);
				try {
					if (np.address() != 0L) { methodName = np.reinterpret(Long.MAX_VALUE).getString(0); }
					if (sp.address() != 0L) { methodSig = sp.reinterpret(Long.MAX_VALUE).getString(0); }
				} finally {
					if (np.address() != 0L) jvmtiDeallocate(np);
					if (sp.address() != 0L) jvmtiDeallocate(sp);
				}
			}

			// -- GetMethodDeclaringClass → GetClassSignature --
			MemorySegment classOut = arena.allocate(ValueLayout.ADDRESS);
			rc = (int) MH_GetMethodDeclaringClass.invokeExact(
			 fpGetMethodDeclaringClass, jvmtiEnvPtr, methodId, classOut);
			if (rc == JVMTI_ERROR_NONE) {
				MemorySegment jclass = classOut.get(ValueLayout.ADDRESS, 0);
				className = fetchClassSig(arena, jclass);
			}
		} catch (Throwable ignored) { }
		return new MethodMeta(className, methodName, methodSig);
	}

	private String fetchClassSig(Arena arena, MemorySegment jclass) {
		try {
			MemorySegment sigPtrOut = arena.allocate(ValueLayout.ADDRESS);
			int rc = (int) MH_GetClassSignature.invokeExact(
			 fpGetClassSignature, jvmtiEnvPtr, jclass,
			 sigPtrOut, MemorySegment.NULL);
			if (rc != JVMTI_ERROR_NONE) return "<unknown>";
			MemorySegment sp = sigPtrOut.get(ValueLayout.ADDRESS, 0);
			if (sp.address() == 0L) return "<unknown>";
			try {
				String sig = sp.reinterpret(Long.MAX_VALUE).getString(0);
				// "Lcom/example/Foo;" → "com.example.Foo"
				if (sig.startsWith("L") && sig.endsWith(";")) { return sig.substring(1, sig.length() - 1).replace('/', '.'); }
				return sig;
			} finally {
				jvmtiDeallocate(sp);
			}
		} catch (Throwable ignored) {
			return "<unknown>";
		}
	}

	/**
	 * Fetches the variable table for a method and converts it to an array of
	 * {@link VarEntry} records.  JVMTI-allocated memory is freed before return.
	 */
	private VarEntry[] fetchVarTable(Arena arena, MemorySegment methodId) {
		try {
			MemorySegment cntOut    = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment tabPtrOut = arena.allocate(ValueLayout.ADDRESS);

			int rc = (int) MH_GetLocalVariableTable.invokeExact(
			 fpGetLocalVariableTable, jvmtiEnvPtr, methodId, cntOut, tabPtrOut);

			if (rc == JVMTI_ERROR_ABSENT_INFO || rc == JVMTI_ERROR_OPAQUE_FRAME) {
				return VarEntry.EMPTY; // compiled without -g, or native frame
			}
			if (rc != JVMTI_ERROR_NONE) return VarEntry.EMPTY;

			int n = cntOut.get(ValueLayout.JAVA_INT, 0);
			if (n <= 0) return VarEntry.EMPTY;

			MemorySegment table = tabPtrOut.get(ValueLayout.ADDRESS, 0)
			 .reinterpret(n * LVE_SIZE);
			VarEntry[] entries = new VarEntry[n];
			try {
				for (int i = 0; i < n; i++) {
					long base     = i * LVE_SIZE;
					long startLoc = table.get(ValueLayout.JAVA_LONG, base + LVE_START_LOC);
					int  length   = table.get(ValueLayout.JAVA_INT, base + LVE_LENGTH);
					int  slot     = table.get(ValueLayout.JAVA_INT, base + LVE_SLOT);

					MemorySegment np = table.get(ValueLayout.ADDRESS, base + LVE_NAME);
					MemorySegment sp = table.get(ValueLayout.ADDRESS, base + LVE_SIGNATURE);

					String name = (np.address() != 0L)
					 ? np.reinterpret(Long.MAX_VALUE).getString(0) : "?";
					String sig = (sp.address() != 0L)
					 ? sp.reinterpret(Long.MAX_VALUE).getString(0) : "?";

					entries[i] = new VarEntry(name, sig, slot, startLoc, length);
				}
			} finally {
				// Free every JVMTI-allocated string, then the table array itself
				for (int i = 0; i < n; i++) {
					long base = i * LVE_SIZE;
					freeIfNonNull(table.get(ValueLayout.ADDRESS, base + LVE_NAME));
					freeIfNonNull(table.get(ValueLayout.ADDRESS, base + LVE_SIGNATURE));
					freeIfNonNull(table.get(ValueLayout.ADDRESS, base + LVE_GENERIC));
				}
				jvmtiDeallocate(tabPtrOut.get(ValueLayout.ADDRESS, 0));
			}
			return entries;
		} catch (Throwable t) {
			return VarEntry.EMPTY;
		}
	}

	//endregion
	//region Low-level helpers

	private void jvmtiDeallocate(MemorySegment ptr) {
		try {
			MH_Deallocate.invokeExact(fpDeallocate, jvmtiEnvPtr, ptr);
		} catch (Throwable ignored) { }
	}

	private void freeIfNonNull(MemorySegment ptr) {
		if (ptr.address() != 0L) jvmtiDeallocate(ptr);
	}

	private static void checkError(int rc, String op) {
		if (rc != JVMTI_ERROR_NONE) { throw new RuntimeException(op + " failed, JVMTI error=" + rc); }
	}

	//endregion
	//region Private value types
	private record MethodMeta(String className, String methodName, String methodSig) { }
	private record VarEntry(String name, String sig, int slot, long startLoc, int length) {
		static final VarEntry[] EMPTY = new VarEntry[0];
	}
	//endregion
}