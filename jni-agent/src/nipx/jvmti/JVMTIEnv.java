package nipx.jvmti;

import nipx.jni.JNIEnv;
import nipx.jni.helper.*;
import nipx.util.LongObjectMap;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;

/**
 * Pure-Panama wrapper around a {@code jvmtiEnv*}.
 *
 * <h3>Depth consistency guarantee</h3>
 * JVMTI depths are measured from the top of the stack at the
 * moment of each call.  This implementation therefore performs all
 * {@code GetLocal*} reads <strong>inline</strong> inside
 * {@link #captureThreadLocals} — never from a nested helper — so the
 * depth values obtained by {@code GetStackTrace} remain valid when passed to
 * {@code GetLocal*} later in the same method body.
 */
public class JVMTIEnv {

	// region Constants
	public static final String UNKNOWN = "<unknown>";

	private static final long ADDR_SIZE = ValueLayout.ADDRESS.byteSize();

	public static final int JVMTI_VERSION_1_2 = 0x30010200;

	public static final int JVMTI_ERROR_NONE             = 0;
	public static final int JVMTI_ERROR_THREAD_NOT_ALIVE = 10;
	public static final int JVMTI_ERROR_OPAQUE_FRAME     = 31;
	public static final int JVMTI_ERROR_INVALID_SLOT     = 35;
	public static final int JVMTI_ERROR_ABSENT_INFO      = 101;
	public static final int JNI_OK                       = 0;

	/** @see <a href=https://pages.cs.wisc.edu/~starr/bots/EISBot-src/html/structjvmtiCapabilities.html>REF</a> */
	public static final  int CAN_TAG_OBJECTS               = 1;
	private static final int CAN_GET_THREAD_STATE          = 1 << 2;
	private static final int CAN_GET_LINE_NUMBERS          = 1 << 12;
	/**
	 * jvmtiCapabilities.can_access_local_variables is bit 14 of the first jint
	 * in the 128-byte bitfield struct.
	 */
	private static final int CAN_ACCESS_LOCAL_VARIABLES    = 1 << 14;
	private static final int CAN_GENERATE_EXCEPTION_EVENTS = 1 << 17;
	private static final int CAN_SUSPEND                   = 1 << 20;

	private static final int  JVMTI_EVENT_EXCEPTION = 58;
	private static final long JVMTICAPS_SIZE        = 16L;

	// -------------------------------------------------------------------------
	// JVMTI function-table slot indices  (0-based, i.e. spec slot N → index N-1)
	// Source: jvmti.h from OpenJDK 22+ (includes GetAllModules at slot 3)
	// -------------------------------------------------------------------------
	public static final  long IDX_SetEventNotificationMode = 1; // slot 2
	private static final long IDX_SuspendThread            = 4;  // slot 5
	private static final long IDX_ResumeThread             = 5;  // slot 6
	private static final long IDX_GetFrameCount            = 15;  // slot 16
	private static final long IDX_GetThreadState           = 16; // slot 17
	private static final long IDX_GetLocalObject           = 20;  // slot 21
	private static final long IDX_GetLocalInt              = 21;  // slot 22
	private static final long IDX_GetLocalLong             = 22;  // slot 23
	private static final long IDX_GetLocalFloat            = 23;  // slot 24
	private static final long IDX_GetLocalDouble           = 24;  // slot 25
	private static final long IDX_Deallocate               = 46;  // slot 47
	private static final long IDX_GetClassSignature        = 47;  // slot 48
	private static final long IDX_GetMethodName            = 63;  // slot 64
	private static final long IDX_GetMethodDeclaringClass  = 64;  // slot 65
	private static final long IDX_GetMethodModifiers       = 65;  // slot 66
	private static final long IDX_GetLineNumberTable       = 69;  // slot 70
	private static final long IDX_GetLocalVariableTable    = 71;  // slot 72
	private static final long IDX_GetCapabilities          = 88;  // slot 89
	private static final long IDX_GetStackTrace            = 103; // slot 104
	private static final long IDX_GetTag                   = 105; // slot 106
	static final         long IDX_SetTag                   = 106; // slot 107
	static final         long IDX_GetObjectsWithTags       = 113; // slot 114
	static final         long IDX_FollowReferences         = 114; // slot 115
	public static final  long IDX_SetEventCallbacks        = 121; // slot 122
	private static final long IDX_GetPotentialCapabilities = 139; // slot 141
	private static final long IDX_AddCapabilities          = 141; // slot 142

	/** JNIInvokeInterface_ index for GetEnv (0-based). */
	private static final long IDX_JavaVM_GetEnv = 6;

	//endregion
	//region jvmtiFrameInfo  { methodID(8) | jlocation/jlong(8) }  = 16 bytes
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

	// ---------------------------------------------------------------------------
	// jvmtiLineNumberEntry layout (64-bit, with natural padding):
	//   0  : jlocation start_location  (8)
	//   8  : jint line_number          (4)
	//   12 : padding                   (4)
	//   total: 16 bytes
	private static final long LNE_SIZE      = 16L;
	private static final long LNE_START_LOC = 0L;
	private static final long LNE_LINE_NUM  = 8L;

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
	private static final MethodHandle MH_AddCapabilities          = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS   // const jvmtiCapabilities*
	 ));
	/** GetPotentialCapabilities(env, caps*) → jint */
	private static final MethodHandle MH_GetPotentialCapabilities = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS   // jvmtiCapabilities* (out)
	 ));
	/** SuspendThread(env, thread) → jint */
	private static final MethodHandle MH_SuspendThread            = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS, // jvmtiEnv*
		ValueLayout.ADDRESS // jthread
	 ));
	/** ResumeThread(env, thread) → jint */
	private static final MethodHandle MH_ResumeThread             = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS, // jvmtiEnv*
		ValueLayout.ADDRESS // jthread
	 ));
	/** GetThreadState(env, thread, thread_state*) → jint */
	private static final MethodHandle MH_GetThreadState           = Linker.nativeLinker().downcallHandle(
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
	static final         MethodHandle MH_SetTag        = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jobject
		ValueLayout.JAVA_LONG  // jlong tag
	 ));

	/** GetObjectsWithTags(env, tag_count, tags, count_ptr, object_result_ptr, tag_result_ptr) → jint */
	static final MethodHandle MH_GetObjectsWithTags = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.JAVA_INT,  // tag_count
		ValueLayout.ADDRESS,   // const jlong* tags
		ValueLayout.ADDRESS,   // jint* count_ptr
		ValueLayout.ADDRESS,   // jobject** object_result_ptr
		ValueLayout.ADDRESS    // jlong** tag_result_ptr
	 ));

	/** FollowReferences(env, heap_filter, klass, initial_object, callbacks, user_data) → jint */
	static final        MethodHandle MH_FollowReferences         = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.JAVA_INT,  // heap_filter
		ValueLayout.ADDRESS,   // klass (NULL means all)
		ValueLayout.ADDRESS,   // initial_object (NULL means from roots)
		ValueLayout.ADDRESS,   // const jvmtiHeapCallbacks* callbacks
		ValueLayout.ADDRESS    // const void* user_data
	 ));
	/** SetEventCallbacks(jvmtiEnv* env, const jvmtiEventCallbacks* callbacks, jint size_of_callbacks) */
	public static final MethodHandle MH_SetEventCallbacks        = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS, // jvmtiEnv*
		ValueLayout.ADDRESS,  // const jvmtiEventCallbacks*
		ValueLayout.JAVA_INT  // jint size_of_callbacks
	 ));
	/** SetEventNotificationMode(jvmtiEnv* env, jint mode, jint event_type, jthread event_thread, ...) */
	public static final MethodHandle MH_SetEventNotificationMode = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS, // jvmtiEnv*
		ValueLayout.JAVA_INT,  // jint mode
		ValueLayout.JAVA_INT,  // jint event_type
		ValueLayout.ADDRESS    // jthread event_thread
	 ));

	/** GetLocalVariableTable(env, methodID, count*, table**) → jint */
	private static final MethodHandle MH_GetLocalVariableTable = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // methodID
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
	private static final MethodHandle MH_GetLocalPrimitive  = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jthread
		ValueLayout.JAVA_INT, // depth
		ValueLayout.JAVA_INT, // slot
		ValueLayout.ADDRESS   // T* out
	 ));
	private static final MethodHandle MH_GetLineNumberTable = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // methodID
		ValueLayout.ADDRESS, // jint* entry_count_ptr
		ValueLayout.ADDRESS   // jvmtiLineNumberEntry** table_ptr
	 ));

	/** GetMethodName(env, methodID, char** name, char** sig, char** generic) → jint */
	private static final MethodHandle MH_GetMethodName = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // methodID
		ValueLayout.ADDRESS,  // char** name_ptr
		ValueLayout.ADDRESS,  // char** signature_ptr
		ValueLayout.ADDRESS   // char** generic_ptr (we pass NULL)
	 ));

	/** GetMethodDeclaringClass(env, methodID, jclass*) → jint */
	static final MethodHandle MH_GetMethodDeclaringClass = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // methodID
		ValueLayout.ADDRESS   // jclass* declaring_class_ptr
	 ));

	/** GetClassSignature(env, jclass, char** sig, char** generic) → jint */
	static final         MethodHandle MH_GetClassSignature  = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // jclass
		ValueLayout.ADDRESS,  // char** signature_ptr
		ValueLayout.ADDRESS   // char** generic_ptr
	 ));
	/** GetMethodModifiers(env, methodID, modifiers*) → jint */
	private static final MethodHandle MH_GetMethodModifiers = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS,  // methodID
		ValueLayout.ADDRESS   // jint* modifiers_ptr
	 ));

	/** Deallocate(env, mem*) → jint */
	private static final MethodHandle MH_Deallocate = Linker.nativeLinker().downcallHandle(
	 FunctionDescriptor.of(ValueLayout.JAVA_INT,
		ValueLayout.ADDRESS,  // jvmtiEnv*
		ValueLayout.ADDRESS   // void* mem
	 ));

	// jvmtiHeapCallbacks struct layout (16 words)
	public static final MemoryLayout       JVMTI_HEAP_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
	 ValueLayout.ADDRESS.withName("heap_iteration_callback"),
	 ValueLayout.ADDRESS.withName("heap_reference_callback"), // need it
	 ValueLayout.ADDRESS.withName("primitive_field_callback"),
	 ValueLayout.ADDRESS.withName("array_primitive_value_callback"),
	 ValueLayout.ADDRESS.withName("string_primitive_value_callback"),
	 ValueLayout.ADDRESS.withName("reserved5"),
	 ValueLayout.ADDRESS.withName("reserved6"),
	 ValueLayout.ADDRESS.withName("reserved7"),
	 ValueLayout.ADDRESS.withName("reserved8"),
	 ValueLayout.ADDRESS.withName("reserved9"),
	 ValueLayout.ADDRESS.withName("reserved10"),
	 ValueLayout.ADDRESS.withName("reserved11"),
	 ValueLayout.ADDRESS.withName("reserved12"),
	 ValueLayout.ADDRESS.withName("reserved13"),
	 ValueLayout.ADDRESS.withName("reserved14"),
	 ValueLayout.ADDRESS.withName("reserved15")
	);
	// jint (JNICALL *jvmtiHeapReferenceCallback) (...)
	static final        FunctionDescriptor HEAP_REF_CALLBACK_DESC      = FunctionDescriptor.of(
	 ValueLayout.JAVA_INT,      // return value (jint)
	 ValueLayout.JAVA_INT,      // reference_kind
	 ValueLayout.ADDRESS,       // reference_info
	 ValueLayout.JAVA_LONG,     // class_tag
	 ValueLayout.JAVA_LONG,     // referrer_class_tag
	 ValueLayout.JAVA_LONG,     // size
	 ValueLayout.ADDRESS,       // jlong* tag_ptr (指向当前被引用对象的tag)
	 ValueLayout.ADDRESS,       // jlong* referrer_tag_ptr (指向指向它的引用者的tag)
	 ValueLayout.JAVA_INT,      // length
	 ValueLayout.ADDRESS        // user_data
	);


	//endregion
	//region Instance state

	public final  MemorySegment jvmtiEnvPtr; // jvmtiEnv*  (struct, first word = fn-table*)
	private final MemorySegment fnTable;     // jvmtiInterface_1_*

	// Cached function pointers
	private final MemorySegment fpGetStackTrace;
	private final MemorySegment fpGetLocalObject;
	private final MemorySegment fpGetLocalInt;
	private final MemorySegment fpGetLocalLong;
	private final MemorySegment fpGetLocalFloat;
	private final MemorySegment fpGetLocalDouble;
	private final MemorySegment fpGetMethodName;
	private final MemorySegment fpGetMethodDeclaringClass;
	private final MemorySegment fpGetMethodModifiers;
	private final MemorySegment fpGetLineNumberTable;
	private final MemorySegment fpGetLocalVariableTable;
	private final MemorySegment fpGetClassSignature;
	private final MemorySegment fpDeallocate;
	private final MemorySegment fpSuspendThread;
	private final MemorySegment fpResumeThread;
	private final MemorySegment fpGetThreadState;
	final         MemorySegment fpSetTag;
	private final MemorySegment fpGetPotentialCapabilities;

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
		fpGetLocalObject = fp(IDX_GetLocalObject);
		fpGetLocalInt = fp(IDX_GetLocalInt);
		fpGetLocalLong = fp(IDX_GetLocalLong);
		fpGetLocalFloat = fp(IDX_GetLocalFloat);
		fpGetLocalDouble = fp(IDX_GetLocalDouble);
		fpGetClassSignature = fp(IDX_GetClassSignature);
		fpGetMethodName = fp(IDX_GetMethodName);
		fpGetMethodDeclaringClass = fp(IDX_GetMethodDeclaringClass);
		fpGetMethodModifiers = fp(IDX_GetMethodModifiers);
		fpGetLineNumberTable = fp(IDX_GetLineNumberTable);
		fpGetLocalVariableTable = fp(IDX_GetLocalVariableTable);
		fpDeallocate = fp(IDX_Deallocate);
		fpSuspendThread = fp(IDX_SuspendThread);
		fpResumeThread = fp(IDX_ResumeThread);
		fpGetThreadState = fp(IDX_GetThreadState);
		fpGetPotentialCapabilities = fp(IDX_GetPotentialCapabilities);

		fpSetTag = fp(IDX_SetTag);

		enableRequiredCapabilities();
	}

	public MemorySegment fp(long index) {
		return fnTable.get(ValueLayout.ADDRESS, index * ADDR_SIZE);
	}

	//endregion
	//region Initialization helpers

	/**
	 * Calls {@code JavaVM::GetEnv(JVMTI_VERSION_1_2)} using the same
	 * {@link JNIEnv#MAIN_VM_POINTER} that {@link JNIEnv} already owns (accessed via
	 * {@link MasterKey}).
	 */
	private static MemorySegment acquireJvmtiEnv() {
		try {
			MemorySegment vmPtr = JNIEnv.getMainVmPointer(); // JavaVM*

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

	/** @see #CAN_ACCESS_LOCAL_VARIABLES */
	private void enableRequiredCapabilities() {
		/* try (Arena arena = Arena.ofConfined()) {
			MemorySegment caps = arena.allocate(16L); // jvmtiCapabilities = 128bit

			int rc = (int) MH_GetPotentialCapabilities.invokeExact(fpGetPotentialCapabilities, jvmtiEnvPtr, caps);
			System.out.printf("GetPotentialCapabilities rc=%d%n", rc);

			// 打印前16字节的bit，定位can_suspend实际在哪一位
			for (int i = 0; i < 16; i++) {
				byte b = caps.get(ValueLayout.JAVA_BYTE, i);
				System.out.printf("byte[%2d] = 0x%02X  %s%n", i, b & 0xFF,
				 Integer.toBinaryString((b & 0xFF) | 0x100).substring(1));
			}
		} catch (Throwable t) {
			throw new RuntimeException(t);
		} */
		try (Arena arena = Arena.ofConfined()) {
			{
				MemorySegment caps = arena.allocate(JVMTICAPS_SIZE, 8);
				caps.set(ValueLayout.JAVA_INT, 0, CAN_TAG_OBJECTS);
				int rc = (int) MH_AddCapabilities.invokeExact(
				 fp(IDX_AddCapabilities), jvmtiEnvPtr, caps);
				if (rc != JVMTI_ERROR_NONE) {
					System.err.println("[E] JVMTI: Failed to add CAN_TAG_OBJECTS capability (rc=" + rc + ")");
				}
			}
			{
				MemorySegment caps = arena.allocate(JVMTICAPS_SIZE, 8);
				// jvmtiCapabilities 位定义 (first jint, offset 0):
				caps.set(ValueLayout.JAVA_INT, 0,
				 CAN_ACCESS_LOCAL_VARIABLES | CAN_GENERATE_EXCEPTION_EVENTS | CAN_GET_LINE_NUMBERS);
				// caps.set(ValueLayout.JAVA_INT, 0, CAN_SUSPEND);

				int rc = (int) MH_AddCapabilities.invokeExact(
				 fp(IDX_AddCapabilities), jvmtiEnvPtr, caps);
				if (rc != JVMTI_ERROR_NONE) {
					// 仅仅记录警告，不要 throw
					System.err.println("[W] JVMTI: Local variable access not available in this phase (rc=" + rc + ")");
				}
			}
			/* MemorySegment caps = arena.allocate(JVMTICAPS_SIZE, 8);
			int rc = (int) Linker.nativeLinker().downcallHandle(FunctionDescriptor.of(ValueLayout.JAVA_INT,
			 ValueLayout.ADDRESS, ValueLayout.ADDRESS)).invokeExact(
			 fp(IDX_GetCapabilities), jvmtiEnvPtr, caps);

			System.out.printf("GetCapabilities rc=%d byte[1]=0x%02X byte[2]=0x%02X%n",
			 rc, caps.get(ValueLayout.JAVA_BYTE, 1), caps.get(ValueLayout.JAVA_BYTE, 2)); */
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}
	//endregion
	//region Public API

	public int getLineNumber(MemorySegment method, long location) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment entryCountOut = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment tablePtrOut   = arena.allocate(ValueLayout.ADDRESS);
			int           rc            = (int) MH_GetLineNumberTable.invokeExact(fpGetLineNumberTable, jvmtiEnvPtr, method, entryCountOut, tablePtrOut);
			if (rc != JVMTI_ERROR_NONE) return -1;

			int           entryCount = entryCountOut.get(ValueLayout.JAVA_INT, 0);
			MemorySegment rawTable   = tablePtrOut.get(ValueLayout.ADDRESS, 0);
			if (entryCount <= 0 || rawTable.address() == 0L) return -1;

			MemorySegment tablePtr = rawTable.reinterpret(entryCount * LNE_SIZE);

			// 二分查找最大的 start_location <= location
			int lo     = 0, hi = entryCount - 1;
			int result = -1;
			while (lo <= hi) {
				int  mid      = (lo + hi) / 2;
				long startLoc = tablePtr.get(ValueLayout.JAVA_LONG, mid * LNE_SIZE + LNE_START_LOC);
				if (startLoc <= location) {
					result = tablePtr.get(ValueLayout.JAVA_INT, mid * LNE_SIZE + LNE_LINE_NUM);
					lo = mid + 1; // 向右
				} else {
					hi = mid - 1; // 向左
				}
			}
			jvmtiDeallocate(rawTable);

			return Math.max(-1, result); // 没有行号信息（比如 native 方法）
		} catch (Throwable e) {
			e.printStackTrace();
			return -1;
		}
	}

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
		return captureThreadLocals(jniEnv, Thread.currentThread(), maxDepth, skipFrames);
	}
	public int getThreadState(MemorySegment jthread) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			// jthread: jthread reference
			MemorySegment stateOut = arena.allocate(ValueLayout.JAVA_INT);
			int           rc       = (int) MH_GetThreadState.invokeExact(fpGetThreadState, jvmtiEnvPtr, jthread, stateOut);
			System.out.printf("GetThreadState rc=%d state=0x%X%n",
			 rc, stateOut.get(ValueLayout.JAVA_INT, 0));
			// rc=10 → jthread 引用本身就是坏的
			// state & 0x20 (JVMTI_THREAD_STATE_SUSPENDED) → 确认是否被挂起
			return stateOut.get(ValueLayout.JAVA_INT, 0);
		}
	}
	public List<FrameLocals> captureThreadLocals(JNIEnv jniEnv, Thread thread, int maxDepth,
	                                             int skipFrames) {
		MemorySegment targetThread;
		GlobalRef     globalRef = null;   // 生命周期手动管理
		if (thread == Thread.currentThread()) {
			targetThread = MemorySegment.NULL;
		} else if (!StackCapture.CAPTURE_LOCALS) {
			return Collections.emptyList();
		} else {
			globalRef = jniEnv.JavaObjectToJObject(thread);
			targetThread = globalRef.ref();
		}
		try {
			return captureThreadLocals(jniEnv, targetThread, maxDepth, skipFrames, thread == Thread.currentThread());
		} finally {
			if (globalRef != null) {
				globalRef.close();
			}
		}
	}

	static final ThreadLocal<MethodMeta[]> metasCache  = ThreadLocal.withInitial(() -> new MethodMeta[128]);
	static final ThreadLocal<VarEntry[][]> tablesCache = ThreadLocal.withInitial(() -> new VarEntry[128][]);
	static final ThreadLocal<long[]>       locsCache   = ThreadLocal.withInitial(() -> new long[128]);
	public List<FrameLocals> captureThreadLocals(JNIEnv jniEnv, MemorySegment targetThread, int maxDepth,
	                                             int skipFrames, boolean isCurrentThread) {
		boolean suspended;
		try {
			if (!isCurrentThread) {
				int rc = (int) MH_SuspendThread.invokeExact(
				 fpSuspendThread, jvmtiEnvPtr, targetThread);
				if (rc != JVMTI_ERROR_NONE) {
					System.err.println("SuspendThread(offset=32) rc=" + rc);
				}
				checkError(rc, "SuspendThread");
			}
			suspended = true;
			// getThreadState(targetThread);
		} catch (Throwable e) {
			// e.printStackTrace();
			suspended = false;
		}

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
			if (rc == JVMTI_ERROR_THREAD_NOT_ALIVE) return Collections.emptyList();
			checkError(rc, "GetStackTrace");

			int frameCount = cntOut.get(ValueLayout.JAVA_INT, 0);

			// ------------------------------------------------------------------
			// 2. Collect method metadata & variable tables (helper calls are fine
			//    here because we have not started calling GetLocal* yet).
			// ------------------------------------------------------------------

			MethodMeta[] metas  = metasCache.get();
			VarEntry[][] tables = tablesCache.get();
			long[]       locs   = locsCache.get();

			for (int d = 0; d < frameCount; d++) {
				long          off = d * FRAME_SIZE;
				MemorySegment mid = frameBuf.get(ValueLayout.ADDRESS, off + FRAME_METHOD_OFF);
				long          loc = frameBuf.get(ValueLayout.JAVA_LONG, off + FRAME_LOCATION_OFF);
				locs[d] = loc;
				long       midAddr = mid.address();
				MethodMeta meta    = metaCache.get(midAddr);
				if (meta == null) {
					meta = fetchMethodMeta(arena, mid); // 仅首次分配
					metaCache.put(midAddr, meta);
				}
				metas[d] = meta;

				// Skip GetLocalVariableTable for frames we are about to discard
				if (d < skipFrames) {
					tables[d] = VarEntry.EMPTY;
					continue;
				}

				VarEntry[] varTable = varCache.get(midAddr);
				if (varTable == null) {
					varTable = fetchVarTable(arena, mid);
					if (varTable == null) varTable = VarEntry.EMPTY;
					varCache.put(midAddr, varTable);
				}
				tables[d] = varTable;
			}

			// ------------------------------------------------------------------
			// 3. Read local values.
			//
			//    *** ALL GetLocal* CALLS MUST STAY IN THIS METHOD ***
			//    Moving them into a helper shifts the depth baseline and makes
			//    slot reads target the wrong frame.
			// ------------------------------------------------------------------
			List<FrameLocals> result = new ArrayList<>(frameCount - skipFrames);
			// 循环内：只存地址，不转换

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
					int    hash  = -1;

					try {
						switch (kind) {
							// ---- object / array --------------------------------
							case 'L', '[' -> {
								MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
								int err = (int) MH_GetLocalObject.invokeExact(
								 fpGetLocalObject, jvmtiEnvPtr,
								 targetThread, d, v.slot, out);

								if (err == JVMTI_ERROR_NONE) {
									MemorySegment localRef = out.get(ValueLayout.ADDRESS, 0);
									if (localRef.address() != 0L) {
										MemorySegment ref = jniEnv.NewGlobalRef(localRef);
										try {
											hash = jniEnv.identityHashCode(ref);
										} finally {
											jniEnv.DeleteGlobalRef(ref);
										}
										value = localRef;
									}
								}
								// System.out.println(err);
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
					} catch (Throwable e) {
						// Any GetLocal* failure → leave value as null
						// e.printStackTrace();
					}

					locals.add(new LocalVariable(v.name, v.sig, v.slot, value, hash));
				}

				MethodMeta meta = metas[d];
				result.add(new FrameLocals(
				 meta.className, meta.methodId, meta.methodName, meta.methodSig,
				 d, loc, locals));
			}

			Arrays.fill(metas, null);
			Arrays.fill(tables, null);
			return result;
		} catch (Throwable t) {
			throw new RuntimeException("captureThreadLocals failed", t);
		} finally {
			if (targetThread != MemorySegment.NULL && suspended) {
				try {
					int rc = (int) MH_ResumeThread.invokeExact(fpResumeThread, jvmtiEnvPtr, targetThread);
					checkError(rc, "ResumeThread");
				} catch (Throwable _) { }
			}
		}
	}

	// MethodMeta 按 method ID 缓存，方法元数据在类生命周期内不变
	private final LongObjectMap<MethodMeta> metaCache = new LongObjectMap<>();
	private final LongObjectMap<VarEntry[]> varCache  = new LongObjectMap<>();
	public void walkThreadFrames(MemorySegment targetThread,
	                             int maxDepth, int skipFrames,
	                             FrameConsumer consumer) {
		walkThreadFrames(targetThread, maxDepth, skipFrames + 1 /* 这里额外加了一层 */, false, consumer);
	}
	public void walkThreadFrames(MemorySegment targetThread,
	                             int maxDepth, int skipFrames, boolean captureThis,
	                             FrameConsumer consumer) {
		try (Arena arena = Arena.ofConfined()) {
			JNIEnv        jniEnv   = JNIEnv.getInstance(arena);
			int           total    = maxDepth + skipFrames;
			MemorySegment frameBuf = arena.allocate(FRAME_SIZE * total, 8);
			MemorySegment cntOut   = arena.allocate(ValueLayout.JAVA_INT);

			int rc = (int) MH_GetStackTrace.invokeExact(
			 fpGetStackTrace, jvmtiEnvPtr,
			 targetThread, 0, total, frameBuf, cntOut);
			if (rc == JVMTI_ERROR_THREAD_NOT_ALIVE) return;
			checkError(rc, "GetStackTrace");

			int frameCount = cntOut.get(ValueLayout.JAVA_INT, 0);

			// 复用数组，零分配
			for (int d = skipFrames; d < frameCount; d++) {
				long          off = d * FRAME_SIZE;
				MemorySegment mid = frameBuf.get(ValueLayout.ADDRESS, off + FRAME_METHOD_OFF);
				// locsBuf[d] = frameBuf.get(ValueLayout.JAVA_LONG, off + FRAME_LOCATION_OFF);

				// 缓存命中时零分配
				long       midAddr = mid.address();
				MethodMeta meta    = metaCache.get(midAddr);
				if (meta == null) {
					meta = fetchMethodMeta(arena, mid); // 仅首次分配
					metaCache.put(midAddr, meta);
				}
				// metasBuf[d] = meta;
				int  flags       = meta.flags;
				long thisAddress = 0;
				if (captureThis && (StackCapture.CAPTURE_LOCALS && !(Modifier.isStatic(flags) || Modifier.isNative(flags)))) {
					try {
						MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
						int err = (int) MH_GetLocalObject.invokeExact(
						 fpGetLocalObject, jvmtiEnvPtr,
						 targetThread, d, 0, out);

						if (err == JVMTI_ERROR_NONE) {
							MemorySegment localRef = out.get(ValueLayout.ADDRESS, 0);
							if (localRef.address() != 0) {
								// 提升为global ref，避免GC
								MemorySegment globalRef = jniEnv.NewGlobalRef(localRef);
								try {
									thisAddress = jniEnv.identityHashCode(globalRef) & 0xFFFFFFFFL;
								} finally {
									jniEnv.DeleteGlobalRef(globalRef);
								}
							}
						}
						// if (err != 13) {
						checkError(err, "GetLocalObject");
						// }
					} catch (Throwable e) {
						// e.printStackTrace();
						thisAddress = 0L;
					}
				}
				if (!consumer.accept(meta.className, meta.methodName, meta.methodSig, thisAddress)) break;
			}

		} catch (Throwable t) {
			throw new RuntimeException("walkCurrentThreadFrames failed", t);
		}
	}
	@FunctionalInterface
	public interface FrameConsumer {
		/**
		 * @param className   类名
		 * @param methodName  方法名
		 * @param methodSig   方法签名
		 * @param thisAddress this 的地址，0 表示静态方法或未找到
		 * @return 是否继续遍历
		 */
		boolean accept(String className, String methodName, String methodSig, long thisAddress);
	}

	//endregion
	//region Metadata helpers  (called before any GetLocal* — depth shift is harmless)

	private MethodMeta fetchMethodMeta(Arena arena, MemorySegment methodId) {
		String className  = UNKNOWN;
		String methodName = UNKNOWN;
		String methodSig  = "";
		int    flags      = -1;
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
					if (np.address() != 0L) methodName = np.reinterpret(Long.MAX_VALUE).getString(0);
					if (sp.address() != 0L) methodSig = sp.reinterpret(Long.MAX_VALUE).getString(0);
				} finally {
					freeIfNonNull(np);
					freeIfNonNull(sp);
				}
			}

			// -- GetMethodDeclaringClass → GetClassSignature --
			MemorySegment classOut = arena.allocate(ValueLayout.ADDRESS);
			rc = getMethodDeclaringClass(methodId, classOut);
			if (rc == JVMTI_ERROR_NONE) {
				MemorySegment jclass = classOut.get(ValueLayout.ADDRESS, 0);
				className = fetchClassSig(arena, jclass);
			}

			// -- GetMethodModifiers --
			MemorySegment modifiersOut = arena.allocate(ValueLayout.JAVA_INT);
			rc = (int) MH_GetMethodModifiers.invokeExact(fpGetMethodModifiers, jvmtiEnvPtr, methodId, modifiersOut);
			if (rc == JVMTI_ERROR_NONE) {
				flags = modifiersOut.get(ValueLayout.JAVA_INT, 0);
			} else {
				flags = 0; // 失败时赋予默认值
			}
		} catch (Throwable e) {
			// e.printStackTrace();
		}
		return new MethodMeta(className, methodId.address(), methodName, methodSig, flags);
	}

	/** @return slashClassName (internalName) */
	public String fetchClassSig(Arena arena, MemorySegment jclass) {
		try {
			MemorySegment sigPtrOut = arena.allocate(ValueLayout.ADDRESS);
			int           rc        = getClassSignature(jclass, sigPtrOut);
			if (rc != JVMTI_ERROR_NONE) return "<unknown>";
			MemorySegment sp = sigPtrOut.get(ValueLayout.ADDRESS, 0);
			if (sp.address() == 0L) return "<unknown>";
			try {
				String sig = sp.reinterpret(Long.MAX_VALUE).getString(0);
				// "Lcom/example/Foo;" → "com.example.Foo"
				if (sig.startsWith("L") && sig.endsWith(";")) { return sig.substring(1, sig.length() - 1); }
				return sig;
			} finally {
				jvmtiDeallocate(sp);
			}
		} catch (Throwable ignored) {
			return "<unknown>";
		}
	}
	public int getMethodDeclaringClass(MemorySegment methodId, MemorySegment classOut) throws Throwable {
		return (int) MH_GetMethodDeclaringClass.invokeExact(
		 fpGetMethodDeclaringClass, jvmtiEnvPtr, methodId, classOut);
	}
	public int getClassSignature(MemorySegment jclass, MemorySegment sigPtrOut) throws Throwable {
		return (int) MH_GetClassSignature.invokeExact(
		 fpGetClassSignature, jvmtiEnvPtr, jclass,
		 sigPtrOut, MemorySegment.NULL);
	}
	public int getClassSignature(MemorySegment jclass, MemorySegment sigPtrOut, MemorySegment genericPtrOut)
	 throws Throwable {
		return (int) MH_GetClassSignature.invokeExact(
		 fpGetClassSignature, jvmtiEnvPtr, jclass,
		 sigPtrOut, genericPtrOut);
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
					 ? np.reinterpret(Long.MAX_VALUE).getString(0) : UNKNOWN;
					String sig = (sp.address() != 0L)
					 ? sp.reinterpret(Long.MAX_VALUE).getString(0) : UNKNOWN;

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
	public void jvmtiDeallocate(MemorySegment ptr) {
		try {
			int _ = (int) MH_Deallocate.invokeExact(fpDeallocate, jvmtiEnvPtr, ptr);
		} catch (Throwable ignored) { }
	}

	public void freeIfNonNull(MemorySegment ptr) {
		if (ptr.address() != 0L) jvmtiDeallocate(ptr);
	}

	static void checkError(int rc, String op) {
		if (rc != JVMTI_ERROR_NONE) { throw new RuntimeException(op + " failed, JVMTI error=" + rc); }
	}

	//endregion
	//region Private value types
	/**
	 * @param className  slashClassName: "com/example/Foo"
	 * @param methodName methodName
	 * @param methodSig  rawMethodSignature: "(FLjava/lang/Runnable)V"
	 * @param flags      methodModifiers {@link Modifier}
	 */
	private record MethodMeta(String className, long methodId, String methodName, String methodSig, int flags) { }
	/**
	 * Variable entry
	 * @param name     variable name
	 * @param sig      variable signature
	 * @param slot     variable slot number
	 * @param startLoc start location of the variable in the method
	 * @param length   length of the variable in the method
	 */
	private record VarEntry(String name, String sig, int slot, long startLoc, int length) {
		static final VarEntry[] EMPTY = new VarEntry[0];
	}
	//endregion

}
