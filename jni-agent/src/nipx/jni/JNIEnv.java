package nipx.jni;

import nipx.jni.helper.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static nipx.jni.helper.NativeHelper.throwable;

/** <a href="https://github.com/dreamlike-ocean/UnsafeJava/blob/master/unsafe-core/src/main/java/top/dreamlike/unsafe/core/panama/jni/JNIEnv.java">JNIEnv</a> */
public class JNIEnv {

	//region Constants & Static Fields

	public static final  int           JNI_VERSION     = 0x00150000;
	private static final MemorySegment MAIN_VM_POINTER = throwable(JNIEnv::initMainVM);
	private static final MethodHandle  MH_GET_JNIENV   = throwable(JNIEnv::initGetJNIEnvMH);

	/** java 与 jni 通信的途径，jobject与Object互转 */
	private static final ThreadLocal<Object> jniToJava = new ThreadLocal<>();

	private static JNIEnv MASTER_ENV;

	private static final MethodHandle NewStringPlatform = throwable(() -> {
		MemorySegment JNU_NewStringPlatformFP = SymbolLookup.loaderLookup()
		 .find("JNU_NewStringPlatform")
		 .get();
		return Linker.nativeLinker()
		 .downcallHandle(FunctionDescriptor.of(
			/*jstring*/ValueLayout.ADDRESS,
			/*JNIEnv *env */ValueLayout.ADDRESS,
			/*const char *str*/ ValueLayout.ADDRESS
		 )).bindTo(JNU_NewStringPlatformFP);
	});

	//endregion

	//region Cache

	/** 方法签名缓存：Method → "([paramSig)returnSig" */
	private static final ConcurrentHashMap<Method, String> METHOD_SIG_CACHE = new ConcurrentHashMap<>(256);

	/** 类名路径缓存：Class.getName() → "pkg/Cls" 格式 */
	private static final ConcurrentHashMap<Class<?>, String> CLASS_PATH_CACHE = new ConcurrentHashMap<>(128);

	/** 可复用的 JValue 写入缓冲区（最大 8 个参数，一般足够） */
	private static final int                 MAX_JVALUES  = 8;
	private static final ThreadLocal<long[]> JVALUE_LONGS = ThreadLocal.withInitial(() -> new long[MAX_JVALUES]);

	//endregion

	//region Instance Fields

	public final  JNIEnvFunctions  functions;
	private final SegmentAllocator allocator;
	private final MemorySegment    jniEnvPointer;

	private final MemorySegment midGetSecret;
	private final MemorySegment midSetSecret;
	private final MemorySegment midIdentityHashCode;
	private final GlobalRef     classJNIEnvRef;
	private final GlobalRef     classSystem;

	//endregion

	//region Constructors
	/** @param jniEnvPointer 必须是当前线程的jniEnv指针 */
	public JNIEnv(SegmentAllocator allocator, MemorySegment jniEnvPointer) {
		if (MASTER_ENV == null) throw new IllegalStateException("Cannot init jniEnvInstance");
		this.allocator = allocator;
		this.jniEnvPointer = jniEnvPointer;
		functions = MASTER_ENV.functions;
		classJNIEnvRef = MASTER_ENV.classJNIEnvRef;
		classSystem = MASTER_ENV.classSystem;
		midGetSecret = MASTER_ENV.midGetSecret;
		midSetSecret = MASTER_ENV.midSetSecret;
		midIdentityHashCode = MASTER_ENV.midIdentityHashCode;
	}

	private JNIEnv(SegmentAllocator allocator) {
		this.allocator = allocator;
		jniEnvPointer = getCurrentThreadEnvPointer();
		functions = new JNIEnvFunctions(jniEnvPointer);

		classJNIEnvRef = getJNIEnvRef(allocator);
		try {
			MemorySegment ref = classJNIEnvRef.ref();
			// 手动获取 mid，避免调用 CallStaticMethodByName
			midGetSecret = getStaticMethodID(ref, "getSecret", "()Ljava/lang/Object;");
			midSetSecret = getStaticMethodID(ref, "setSecret", "(Ljava/lang/Object;)V");
		} catch (Throwable t) { throw new RuntimeException(t); }

		classSystem = FindClass(System.class);
		try {
			// System#identityHashCode
			midIdentityHashCode = getStaticMethodID(classSystem.ref(), "identityHashCode", "(Ljava/lang/Object;)I");
		} catch (Throwable t) { throw new RuntimeException(t); }
		MASTER_ENV = this;
	}

	public static JNIEnv getInstance(SegmentAllocator allocator) {
		return new JNIEnv(allocator, getCurrentThreadEnvPointer());
	}
	//endregion

	//region JNI Environment Initialization
	static { MASTER_ENV = new JNIEnv(Arena.global()); }

	public static void load() { }
	private GlobalRef getJNIEnvRef(SegmentAllocator allocator) {
		GlobalRef     jstr        = null;
		MemorySegment threadClass = null, classClass = null, currentThread = null, classLoader = null;
		try {
			threadClass = NewGlobalRef(findClassDirect("java/lang/Thread"));
			classClass = NewGlobalRef(findClassDirect("java/lang/Class"));

			var midCurrentThread = getStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
			var midGetContextCL  = getMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
			var midForName = getStaticMethodID(classClass, "forName",
			 "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");

			currentThread = NewGlobalRef(callStaticObjectMethodA(threadClass, midCurrentThread, MemorySegment.NULL)); // Thread.currentThread()
			classLoader = NewGlobalRef(callObjectMethodA(currentThread, midGetContextCL, MemorySegment.NULL)); // Thread.currentThread().getContextClassLoader()

			jstr = cstrToJstring(allocator.allocateFrom(JNIEnv.class.getName()));
			var args = allocator.allocate(JValue.jvalueLayout, 3);
			args.copyFrom(MemorySegment.ofArray(new long[]{
			 jstr.ref().address(),
			 0L, // false
			 classLoader.address()
			}));

			var jniEnvClass = callStaticObjectMethodA(classClass, midForName, args);
			return new GlobalRef(this, jniEnvClass);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		} finally {
			if (jstr != null) jstr.close();
			if (threadClass != null) DeleteGlobalRef(threadClass);
			if (classClass != null) DeleteGlobalRef(classClass);
			if (currentThread != null) DeleteGlobalRef(currentThread);
			if (classLoader != null) DeleteGlobalRef(classLoader);
		}
	}

	public static MemorySegment getCurrentThreadEnvPointer() {
		try {
			return ((MemorySegment) MH_GET_JNIENV.invokeExact(MAIN_VM_POINTER, JNI_VERSION))
			 .reinterpret(Long.MAX_VALUE);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	private static MemorySegment initMainVM() throws Throwable {
		Runtime.getRuntime().loadLibrary("java");
		String javaHomePath = System.getProperty("java.home", "");
		if (javaHomePath.isBlank()) {
			throw new RuntimeException("cant find java.home!");
		}
		//根据当前系统判断使用哪个后缀名
		String libName = System.mapLibraryName("jvm");

		String jvmPath = javaHomePath + "/lib/server/" + libName;
		if (!Files.exists(Path.of(javaHomePath + "/lib/server/" + libName))) {
			jvmPath = javaHomePath + "/bin/server/" + libName;
		}
		Runtime.getRuntime().load(jvmPath);
		MemorySegment jniGetCreatedJavaVM_FP = SymbolLookup.loaderLookup()
		 .find("JNI_GetCreatedJavaVMs")
		 .get();
		MethodHandle JNI_GetCreatedJavaVM_MH = Linker.nativeLinker()
		 .downcallHandle(
			FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
		 )
		 .bindTo(jniGetCreatedJavaVM_FP);
		Arena         global = Arena.global();
		MemorySegment vm     = global.allocate(ValueLayout.ADDRESS);
		MemorySegment numVMs = global.allocate(ValueLayout.JAVA_INT);
		//jdk22和其他版本 兼容使用
		numVMs.set(ValueLayout.JAVA_INT, 0, 0);
		int i = (int) JNI_GetCreatedJavaVM_MH.invokeExact(vm, 1, numVMs);
		return vm.get(ValueLayout.ADDRESS, 0);
	}

	private static MethodHandle initGetJNIEnvMH() {
		MemorySegment JNU_GetEnv_FP = SymbolLookup.loaderLookup()
		 .find("JNU_GetEnv")
		 .get();
		return Linker.nativeLinker()
		 .downcallHandle(FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
		 .bindTo(JNU_GetEnv_FP);
	}

	//endregion

	//region JNI Private Helpers

	private MemorySegment findClassDirect(String slashName) {
		return throwable(() -> (MemorySegment) JNIEnvFunctions.FindClass_MH.invokeExact(
		 functions.FindClassFp, jniEnvPointer, allocator.allocateFrom(slashName)));
	}

	private MemorySegment getMethodID(MemorySegment cls, String name, String sig) {
		return throwable(() -> (MemorySegment) JNIEnvFunctions.GetMethodID_MH.invokeExact(
		 functions.GetMethodIDFp, jniEnvPointer, cls,
		 allocator.allocateFrom(name), allocator.allocateFrom(sig)));
	}

	private MemorySegment callStaticObjectMethodA(MemorySegment cls, MemorySegment mid, MemorySegment jvalues) {
		return throwable(() -> (MemorySegment) JNIEnvFunctions.CallStaticObjectMethodA_MH.invokeExact(
		 functions.CallStaticObjectMethodAFp, jniEnvPointer, cls, mid, jvalues));
	}

	private MemorySegment callObjectMethodA(MemorySegment obj, MemorySegment mid, MemorySegment jvalues) {
		return throwable(() -> (MemorySegment) JNIEnvFunctions.CallObjectMethodA_MH.invokeExact(
		 functions.CallObjectMethodAFp, jniEnvPointer, obj, mid, jvalues));
	}

	/** @return 底层的 方法ID */
	private MemorySegment getStaticMethodID(MemorySegment cls, String name, String sig) throws Throwable {
		return (MemorySegment) JNIEnvFunctions.GetStaticMethodID_MH.invokeExact(
		 functions.GetStaticMethodIDFp, jniEnvPointer, cls,
		 allocator.allocateFrom(name), allocator.allocateFrom(sig));
	}

	/** for JNI */
	private static Object getSecret() {
		return jniToJava.get();
	}

	private static void setSecret(Object o) {
		jniToJava.set(o);
	}

	private GlobalRef cstrToJstring(MemorySegment cstr) {
		return throwable(() -> new GlobalRef(this, (MemorySegment) NewStringPlatform.invokeExact(jniEnvPointer, cstr)));
	}

	//endregion
	//region Global Reference Management

	public MemorySegment NewGlobalRef(MemorySegment jobject) {
		try {
			return (MemorySegment) JNIEnvFunctions.NewGlobalRef_MH.invokeExact(functions.NewGlobalRefFp, jniEnvPointer, jobject);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public void DeleteGlobalRef(MemorySegment globalRef) {
		try {
			JNIEnvFunctions.DeleteGlobalRef_MH.invokeExact(functions.DeleteGlobalRefFp, jniEnvPointer, globalRef);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public GlobalRef FindClass(Class<?> c) {
		return JavaObjectToJObject(c);
	}

	//endregion

	//region Field Operations

	public GlobalRef GetStaticFieldByName(Field field) {
		if (!Modifier.isStatic(field.getModifiers())) {
			throw new IllegalArgumentException("only support static field");
		}
		return throwable(() -> {
			try (var clsRef = FindClass(field.getDeclaringClass())) {
				var fidRef = (MemorySegment) JNIEnvFunctions.GetStaticFieldID_MH.invokeExact(
				 functions.GetStaticFieldIDFp,
				 jniEnvPointer,
				 clsRef.ref(),
				 allocator.allocateFrom(field.getName()),
				 allocator.allocateFrom(NativeHelper.classToSig(field.getType()))
				);
				boolean       isRef = false;
				MemorySegment ref   = null;
				long value = switch (field.getType().getName()) {
					case "boolean" ->
					 (boolean) JNIEnvFunctions.GetStaticBooleanField_MH.invokeExact(functions.GetStaticBooleanFieldFp, jniEnvPointer, clsRef.ref(), fidRef) ? 1 : 0;
					case "byte" ->
					 (long) JNIEnvFunctions.GetStaticByteField_MH.invokeExact(functions.GetStaticByteFieldFp, jniEnvPointer, clsRef.ref(), fidRef);
					case "char" ->
					 (long) JNIEnvFunctions.GetStaticCharField_MH.invokeExact(functions.GetStaticCharFieldFp, jniEnvPointer, clsRef.ref(), fidRef);
					case "short" ->
					 (long) JNIEnvFunctions.GetStaticShortField_MH.invokeExact(functions.GetStaticShortFieldFp, jniEnvPointer, clsRef.ref(), fidRef);
					case "int" ->
					 (long) JNIEnvFunctions.GetStaticIntField_MH.invokeExact(functions.GetStaticIntFieldFp, jniEnvPointer, clsRef.ref(), fidRef);
					case "long" ->
					 (long) JNIEnvFunctions.GetStaticLongField_MH.invokeExact(functions.GetStaticLongFieldFp, jniEnvPointer, clsRef.ref(), fidRef);
					case "float" ->
					 Float.floatToRawIntBits((float) JNIEnvFunctions.GetStaticFloatField_MH.invokeExact(functions.GetStaticFloatFieldFp, jniEnvPointer, clsRef.ref(), fidRef)) & 0xFFFFFFFFL;
					case "double" ->
					 Double.doubleToRawLongBits((double) JNIEnvFunctions.GetStaticDoubleField_MH.invokeExact(functions.GetStaticDoubleFieldFp, jniEnvPointer, clsRef.ref(), fidRef));
					default -> {
						isRef = true;
						ref = (MemorySegment) JNIEnvFunctions.GetStaticObjectField_MH.invokeExact(functions.GetStaticObjectFieldFp, jniEnvPointer, clsRef.ref(), fidRef);
						yield 0;
					}
				};
				if (isRef) {
					return new GlobalRef(this, ref);
				}
				return new GlobalRef(this, new JValue(value));
			}
		});
	}

	public void SetStaticFieldByName(Field field, GlobalRef value) {
		if (!Modifier.isStatic(field.getModifiers())) {
			throw new IllegalArgumentException("only support static field");
		}
		throwable(() -> {
			Class<?> aClass = field.getDeclaringClass();

			try (GlobalRef clsRef = FindClass(aClass);) {
				var fidRef = (MemorySegment) JNIEnvFunctions.GetStaticFieldID_MH.invokeExact(
				 functions.GetStaticFieldIDFp,
				 jniEnvPointer,
				 clsRef.ref(),
				 allocator.allocateFrom(field.getName()),
				 allocator.allocateFrom(NativeHelper.classToSig(field.getType()))
				);
				switch (field.getType().getName()) {
					case "boolean" ->
					 JNIEnvFunctions.SetStaticBooleanField_MH.invokeExact(functions.SetStaticBooleanFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.jValue.getBoolean());
					case "byte" ->
					 JNIEnvFunctions.SetStaticByteField_MH.invokeExact(functions.SetStaticByteFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.jValue.getByte());
					case "char" ->
					 JNIEnvFunctions.SetStaticCharField_MH.invokeExact(functions.SetStaticCharFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.jValue.getChar());
					case "short" ->
					 JNIEnvFunctions.SetStaticShortField_MH.invokeExact(functions.SetStaticShortFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.jValue.getShort());
					case "int" ->
					 JNIEnvFunctions.SetStaticIntField_MH.invokeExact(functions.SetStaticIntFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.jValue.getInt());
					case "long" ->
					 JNIEnvFunctions.SetStaticLongField_MH.invokeExact(functions.SetStaticLongFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.jValue.getLong());
					case "float" ->
					 JNIEnvFunctions.SetStaticFloatField_MH.invokeExact(functions.SetStaticFloatFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.jValue.getFloat());
					case "double" ->
					 JNIEnvFunctions.SetStaticDoubleField_MH.invokeExact(functions.SetStaticDoubleFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.jValue.getDouble());
					default ->
					 JNIEnvFunctions.SetStaticObjectField_MH.invokeExact(functions.SetStaticObjectFieldFp, jniEnvPointer, clsRef.ref(), fidRef, value.ref());
				}
			}

		});
	}

	public GlobalRef GetFieldByName(Field field, GlobalRef jobject) {
		if (Modifier.isStatic(field.getModifiers())) {
			throw new IllegalArgumentException("only support not static field");
		}
		return throwable(() -> {
			try (var clsRef = FindClass(field.getDeclaringClass())) {
				var fidRef = (MemorySegment) JNIEnvFunctions.GetFieldId.invokeExact(
				 functions.GetFieldIDFp,
				 jniEnvPointer,
				 clsRef.ref(),
				 allocator.allocateFrom(field.getName()),
				 allocator.allocateFrom(NativeHelper.classToSig(field.getType()))
				);
				boolean       isRef = false;
				MemorySegment ref   = null;
				long value = switch (field.getType().getName()) {
					case "boolean" ->
					 (boolean) JNIEnvFunctions.GetBooleanField.invokeExact(functions.GetBooleanFieldFp, jniEnvPointer, jobject.ref(), fidRef) ? 1 : 0;
					case "byte" ->
					 (long) JNIEnvFunctions.GetByteField.invokeExact(functions.GetByteFieldFp, jniEnvPointer, jobject.ref(), fidRef);
					case "char" ->
					 (long) JNIEnvFunctions.GetCharField.invokeExact(functions.GetCharFieldFp, jniEnvPointer, jobject.ref(), fidRef);
					case "short" ->
					 (long) JNIEnvFunctions.GetShortField.invokeExact(functions.GetShortFieldFp, jniEnvPointer, jobject.ref(), fidRef);
					case "int" ->
					 (long) JNIEnvFunctions.GetIntField.invokeExact(functions.GetIntFieldFp, jniEnvPointer, jobject.ref(), fidRef);
					case "long" ->
					 (long) JNIEnvFunctions.GetLongField.invokeExact(functions.GetLongFieldFp, jniEnvPointer, jobject.ref(), fidRef);
					case "float" ->
					 Float.floatToRawIntBits((float) JNIEnvFunctions.GetFloatField.invokeExact(functions.GetFloatFieldFp, jniEnvPointer, jobject.ref(), fidRef)) & 0xFFFFFFFFL;
					case "double" ->
					 Double.doubleToRawLongBits((double) JNIEnvFunctions.GetDoubleField.invokeExact(functions.GetDoubleFieldFp, jniEnvPointer, jobject.ref(), fidRef));
					default -> {
						isRef = true;
						ref = (MemorySegment) JNIEnvFunctions.GetObjectField.invokeExact(functions.GetObjectFieldFp, jniEnvPointer, jobject.ref(), fidRef);
						yield 0;
					}
				};
				if (isRef) {
					return new GlobalRef(this, ref);
				}
				return new GlobalRef(this, new JValue(value));
			}
		});
	}

	public void SetFieldByName(Field field, GlobalRef target, GlobalRef fieldValue) {
		if (Modifier.isStatic(field.getModifiers())) {
			throw new IllegalArgumentException("only support not static field");
		}
		throwable(() -> {
			try (var clsRef = FindClass(field.getDeclaringClass())) {
				var fidRef = (MemorySegment) JNIEnvFunctions.GetFieldId.invokeExact(
				 functions.GetFieldIDFp,
				 jniEnvPointer,
				 clsRef.ref(),
				 allocator.allocateFrom(field.getName()),
				 allocator.allocateFrom(NativeHelper.classToSig(field.getType()))
				);
				switch (field.getType().getName()) {
					case "boolean" ->
					 JNIEnvFunctions.SetBooleanField.invokeExact(functions.SetBooleanFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.jValue.getBoolean());
					case "byte" ->
					 JNIEnvFunctions.SetByteField.invokeExact(functions.SetByteFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.jValue.getByte());
					case "char" ->
					 JNIEnvFunctions.SetCharField.invokeExact(functions.SetCharFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.jValue.getChar());
					case "short" ->
					 JNIEnvFunctions.SetShortField.invokeExact(functions.SetShortFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.jValue.getShort());
					case "int" ->
					 JNIEnvFunctions.SetIntField.invokeExact(functions.SetIntFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.jValue.getInt());
					case "long" ->
					 JNIEnvFunctions.SetLongField.invokeExact(functions.SetLongFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.jValue.getLong());
					case "float" ->
					 JNIEnvFunctions.SetFloatField.invokeExact(functions.SetFloatFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.jValue.getFloat());
					case "double" ->
					 JNIEnvFunctions.SetDoubleField.invokeExact(functions.SetDoubleFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.jValue.getDouble());
					default ->
					 JNIEnvFunctions.SetObjectField.invokeExact(functions.SetObjectFieldFp, jniEnvPointer, target.ref(), fidRef, fieldValue.ref());
				}
			}
		});
	}

	//endregion

	//region Method Invocation

	public GlobalRef CallStaticMethodByName(Method method) {
		return CallStaticMethodByName(method, MemorySegment.NULL, "()" + NativeHelper.classToSig(method.getReturnType()));
	}

	public GlobalRef CallStaticMethodByName(Method method, MemorySegment jvalues) {
		String paramSig = Arrays.stream(method.getParameters())
		 .map(Parameter::getType)
		 .map(NativeHelper::classToSig)
		 .collect(Collectors.joining());
		return CallStaticMethodByName(method, jvalues, "(" + paramSig + ")" + NativeHelper.classToSig(method.getReturnType()));
	}

	public GlobalRef CallStaticMethodByName(Method method, JValue... jvalues) {
		String paramSig = Arrays.stream(method.getParameters())
		 .map(Parameter::getType)
		 .map(NativeHelper::classToSig)
		 .collect(Collectors.joining());
		long[] longs = new long[jvalues.length];
		for (int i = 0; i < jvalues.length; i++) {
			longs[i] = jvalues[i].getLong();
		}
		MemorySegment jValuesPtr = allocator.allocate(JValue.jvalueLayout, jvalues.length);
		jValuesPtr.copyFrom(MemorySegment.ofArray(longs));
		return CallStaticMethodByName(method, jValuesPtr, "(" + paramSig + ")" + NativeHelper.classToSig(method.getReturnType()));
	}

	/**
	 * 只用于调用系统加载器加载的类中的静态方法
	 * 原因在于：对应的jni实现里面先获取类加载器是查找vframe顶层的栈帧，拿到这个栈帧的owner，然后用这个owner所属的类加载器去找到对应的类
	 * 而在这里顶层栈帧归属于MethodHandle,所以找到的类加载器是系统类加载器，所以只能调用系统类加载器加载的类
	 * @param method 需要调用的方法
	 */
	public GlobalRef CallStaticMethodByName(Method method, MemorySegment jvalues, String sig) {
		if (method.getParameters().length * JValue.jvalueLayout.byteSize() != jvalues.byteSize()) {
			throw new IllegalArgumentException("jvalues size not match");
		}
		if (!Modifier.isStatic(method.getModifiers())) {
			throw new IllegalArgumentException("only support static method");
		}
		Class<?> ownerClass = method.getDeclaringClass();
		String   methodName = method.getName();
		return throwable(() -> {
			//todo解析错误的问题
			try (GlobalRef jclassRef = FindClass(ownerClass)) {
				MemorySegment mid   = (MemorySegment) JNIEnvFunctions.GetStaticMethodID_MH.invokeExact(functions.GetStaticMethodIDFp, jniEnvPointer, jclassRef.ref(), allocator.allocateFrom(methodName), allocator.allocateFrom(sig));
				boolean       isRef = false;
				MemorySegment ref   = null;
				long jvalue = switch (method.getReturnType().getName()) {
					case "void" -> {
						JNIEnvFunctions.CallStaticVoidMethodA_MH.invokeExact(functions.CallStaticVoidMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues);
						yield 0L;
					}
					case "int" ->
					 (long) JNIEnvFunctions.CallStaticIntMethodA_MH.invokeExact(functions.CallStaticIntMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues);
					case "boolean" ->
					 (boolean) JNIEnvFunctions.CallStaticBooleanMethodA_MH.invokeExact(functions.CallStaticBooleanMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues) ? 1 : 0;
					case "byte" ->
					 (long) JNIEnvFunctions.CallStaticByteMethodA_MH.invokeExact(functions.CallStaticByteMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues);
					case "char" ->
					 (long) JNIEnvFunctions.CallStaticCharMethodA_MH.invokeExact(functions.CallStaticCharMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues);
					case "short" ->
					 (long) JNIEnvFunctions.CallStaticShortMethodA_MH.invokeExact(functions.CallStaticShortMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues);
					case "long" ->
					 (long) JNIEnvFunctions.CallStaticLongMethodA_MH.invokeExact(functions.CallStaticLongMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues);
					case "float" ->
					 Float.floatToRawIntBits((float) JNIEnvFunctions.CallStaticFloatMethodA_MH.invokeExact(functions.CallStaticFloatMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues)) & 0xFFFFFFFFL;
					case "double" ->
					 Double.doubleToRawLongBits((double) JNIEnvFunctions.CallStaticDoubleMethodA_MH.invokeExact(functions.CallStaticDoubleMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues));
					default -> {
						isRef = true;
						ref = (MemorySegment) JNIEnvFunctions.CallStaticObjectMethodA_MH.invokeExact(functions.CallStaticObjectMethodAFp, jniEnvPointer, jclassRef.ref(), mid, jvalues);
						yield 0;
					}
				};
				if (isRef) {
					return new GlobalRef(this, ref);
				}
				return new GlobalRef(this, new JValue(jvalue));
			}
		});
	}

	public GlobalRef CallMethodByName(Method method, MemorySegment jobject) {
		return CallMethodByName(method, jobject, MemorySegment.NULL, "()" + NativeHelper.classToSig(method.getReturnType()));
	}

	public GlobalRef CallMethodByName(Method method, MemorySegment jobject, MemorySegment jvalues) {
		String paramSig = Arrays.stream(method.getParameters())
		 .map(Parameter::getType)
		 .map(NativeHelper::classToSig)
		 .collect(Collectors.joining());
		return CallMethodByName(method, jobject, jvalues, "(" + paramSig + ")" + NativeHelper.classToSig(method.getReturnType()));
	}

	public GlobalRef CallMethodByName(Method method, MemorySegment jobject, MemorySegment jvalues, String sig) {
		if (method.getParameters().length * JValue.jvalueLayout.byteSize() != jvalues.byteSize()) {
			throw new IllegalArgumentException("jvalues size not match");
		}
		if (Modifier.isStatic(method.getModifiers())) {
			throw new IllegalArgumentException("only support not static method");
		}
		String methodName = method.getName();
		return throwable(() -> {
			boolean       isRef = false;
			MemorySegment clazz = (MemorySegment) JNIEnvFunctions.GetObjectClass_MH.invokeExact(functions.GetObjectClassFp, jniEnvPointer, jobject);
			try (GlobalRef ref = new GlobalRef(this, clazz)) {
				MemorySegment rref = null;
				MemorySegment mid  = (MemorySegment) JNIEnvFunctions.GetMethodID_MH.invokeExact(functions.GetMethodIDFp, jniEnvPointer, ref.ref(), allocator.allocateFrom(methodName), allocator.allocateFrom(sig));
				long returnValue = switch (method.getReturnType().getName()) {
					case "void" -> {
						JNIEnvFunctions.CallVoidMethodA_MH.invokeExact(functions.CallVoidMethodAFp, jniEnvPointer, jobject, mid, jvalues);
						yield 0L;
					}
					case "int" ->
					 (long) JNIEnvFunctions.CallIntMethodA_MH.invokeExact(functions.CallIntMethodAFp, jniEnvPointer, jobject, mid, jvalues);
					case "boolean" ->
					 (boolean) JNIEnvFunctions.CallBooleanMethodA_MH.invokeExact(functions.CallBooleanMethodAFp, jniEnvPointer, jobject, mid, jvalues) ? 1 : 0;
					case "byte" ->
					 (long) JNIEnvFunctions.CallByteMethodA_MH.invokeExact(functions.CallByteMethodAFp, jniEnvPointer, jobject, mid, jvalues);
					case "char" ->
					 (long) JNIEnvFunctions.CallCharMethodA_MH.invokeExact(functions.CallCharMethodAFp, jniEnvPointer, jobject, mid, jvalues);
					case "short" ->
					 (long) JNIEnvFunctions.CallShortMethodA_MH.invokeExact(functions.CallShortMethodAFp, jniEnvPointer, jobject, mid, jvalues);
					case "long" ->
					 (long) JNIEnvFunctions.CallLongMethodA_MH.invokeExact(functions.CallLongMethodAFp, jniEnvPointer, jobject, mid, jvalues);
					case "float" ->
					 Float.floatToRawIntBits((float) JNIEnvFunctions.CallFloatMethodA_MH.invokeExact(functions.CallFloatMethodAFp, jniEnvPointer, jobject, mid, jvalues)) & 0xFFFFFFFFL;
					case "double" ->
					 Double.doubleToRawLongBits((double) JNIEnvFunctions.CallDoubleMethodA_MH.invokeExact(functions.CallDoubleMethodAFp, jniEnvPointer, jobject, mid, jvalues));
					default -> {
						isRef = true;
						rref = (MemorySegment) JNIEnvFunctions.CallObjectMethodA_MH.invokeExact(functions.CallObjectMethodAFp, jniEnvPointer, jobject, mid, jvalues);
						yield 0;
					}
				};
				if (isRef) {
					return new GlobalRef(this, rref);
				}
				return new GlobalRef(this, new JValue(returnValue));
			}

		});
	}

	//endregion

	//region Java / JNI Object Conversion

	public Object jObjectToJavaObject(MemorySegment jobject) {
		return throwable(() -> {
			MemorySegment jValuesPtr = allocator.allocate(JValue.jvalueLayout, 1);
			long          address    = jobject.address();
			if (address == 0) return null;
			jValuesPtr.copyFrom(MemorySegment.ofArray(new long[]{address}));
			JNIEnvFunctions.CallStaticVoidMethodA_MH.invokeExact(
			 functions.CallStaticVoidMethodAFp, jniEnvPointer, classJNIEnvRef.ref(), midSetSecret, jValuesPtr);
			Object res = jniToJava.get();
			jniToJava.remove();
			return res;
		});
	}

	public GlobalRef JavaObjectToJObject(Object o) {
		return throwable(() -> {
			// 直接调用 JNI，不经过 Java 的 Method.invoke，不查找类
			setSecret(o);
			try {
				MemorySegment localRef = (MemorySegment) JNIEnvFunctions.CallStaticObjectMethodA_MH.invokeExact(
				 functions.CallStaticObjectMethodAFp, jniEnvPointer, classJNIEnvRef.ref(), midGetSecret, MemorySegment.NULL);
				return new GlobalRef(this, localRef);
			} finally {
				jniToJava.remove();
			}
		});
	}

	//endregion

	//region Other Public Methods

	public int identityHashCode(MemorySegment ref) {
		return throwable(() -> {
			MemorySegment jValuesPtr = allocator.allocate(JValue.jvalueLayout, 1);
			jValuesPtr.copyFrom(MemorySegment.ofArray(new long[]{JValue.getLong(ref.address())}));
			return (int) JNIEnvFunctions.CallStaticIntMethodA_MH.invokeExact(
			 functions.CallStaticIntMethodAFp,
			 jniEnvPointer, classSystem.ref(), midIdentityHashCode, jValuesPtr);
		});
	}

	public GlobalRef newObject(Constructor ctr, JValue... jValues) {
		Parameter[] parameters = ctr.getParameters();
		if (parameters.length != jValues.length) {
			throw new IllegalArgumentException("jValues size not match");
		}
		String sig = Arrays.stream(parameters)
		 .map(Parameter::getType)
		 .map(NativeHelper::classToSig)
		 .collect(Collectors.joining());
		String methodSig = "(" + sig + ")V";

		return throwable(() -> {
			MemorySegment jValuesPtr = allocator.allocate(JValue.jvalueLayout, jValues.length);
			for (int i = 0; i < jValues.length; i++) {
				jValuesPtr.set(ValueLayout.JAVA_LONG, i * JValue.jvalueLayout.byteSize(), jValues[i].getLong());
			}
			try (GlobalRef clsRef = FindClass(ctr.getDeclaringClass())) {
				MemorySegment mid       = (MemorySegment) JNIEnvFunctions.GetMethodID_MH.invokeExact(functions.GetMethodIDFp, jniEnvPointer, clsRef.ref(), allocator.allocateFrom("<init>"), allocator.allocateFrom(methodSig));
				MemorySegment newobject = (MemorySegment) JNIEnvFunctions.NewObject_MH.invokeExact(functions.NewObjectAFp, jniEnvPointer, clsRef.ref(), mid, jValuesPtr);
				return new GlobalRef(this, newobject);
			}
		});
	}

	public MemorySegment getJniEnvPointer() {
		return jniEnvPointer;
	}

	public static MemorySegment getMainVmPointer() {
		return MAIN_VM_POINTER;
	}

	public MemorySegment GetObjectClass(MemorySegment ref) {
		return throwable(() -> (MemorySegment) JNIEnvFunctions.GetObjectClass_MH.invokeExact(functions.GetObjectClassFp, jniEnvPointer, ref));
	}

	public boolean IsSameObject(MemorySegment m1, MemorySegment m2) {
		return throwable(() -> (boolean) JNIEnvFunctions.IsSameObject_MH.invokeExact(functions.IsSameObjectFp, jniEnvPointer, m1, m2));
	}

	//endregion
}