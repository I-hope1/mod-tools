package nipx.jni;

import nipx.jni.helper.GlobalRef;
import nipx.jni.helper.NativeHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("rawtypes")
public class RegisterNative {
	private static final MemoryLayout JNI_NATIVE_METHOD_LAYOUT = MemoryLayout.structLayout(
	 ValueLayout.ADDRESS, /*name*/
	 ValueLayout.ADDRESS, /*signature*/
	 ValueLayout.ADDRESS  /*fnPtr*/
	);

	private static final VarHandle nameVH      = JNI_NATIVE_METHOD_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(0));
	private static final VarHandle signatureVH = JNI_NATIVE_METHOD_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(1));
	private static final VarHandle fnPtrVH     = JNI_NATIVE_METHOD_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(2));

	private static final MethodHandle registerNativeMH = Linker.nativeLinker()
	 .downcallHandle(
		FunctionDescriptor.of(
		 ValueLayout.JAVA_INT,
		 ValueLayout.ADDRESS /*JNIEnv *env */,
		 ValueLayout.ADDRESS /*jclass*/,
		 ValueLayout.ADDRESS /*method*/,
		 ValueLayout.JAVA_INT /*nMethods*/
		)
	 );

	public record MethodBinderRequest(Method source, MethodHandle target) {
	}

	public static void nativeBinder(Class clazz, Method source, MethodHandle target) {
		nativeBinder(null, clazz, List.of(new MethodBinderRequest(source, target)), Arena.global());
	}

	public static void nativeBinder(JNIEnv jniEnv, Class clazz, Method source, MethodHandle target) {
		nativeBinder(jniEnv, clazz, List.of(new MethodBinderRequest(source, target)), Arena.global());
	}

	public static void nativeBinder(Class clazz, MethodBinderRequest... requests) {
		nativeBinder(null, clazz, Arrays.asList(requests), Arena.global());
	}

	public static void nativeBinder(JNIEnv jniEnv, Class clazz, List<MethodBinderRequest> methodBinderRequests) {
		nativeBinder(jniEnv, clazz, methodBinderRequests, Arena.global());
	}

	public static void nativeBinder(JNIEnv jniEnv, Class clazz, List<MethodBinderRequest> methodBinderRequests, Arena stubArena) {
		if (methodBinderRequests == null || methodBinderRequests.isEmpty()) {
			return;
		}
		if (jniEnv == null) {
			jniEnv = JNIEnv.getInstance();
		}
		if (stubArena == null) {
			stubArena = Arena.global();
		}

		MemorySegment registerNativesFp = jniEnv.functions.RegisterNativesFp;

		try (
		 Arena arena = Arena.ofConfined();
		 GlobalRef jclassRef = jniEnv.FindClass(clazz);
		) {
			MemorySegment methods = arena.allocate(JNI_NATIVE_METHOD_LAYOUT, methodBinderRequests.size());

			int i = 0;
			for (MethodBinderRequest request : methodBinderRequests) {
				Method source = request.source;
				String name   = source.getName();
				String paramSig = Arrays.stream(source.getParameterTypes())
				 .map(NativeHelper::classToSig)
				 .collect(Collectors.joining());
				String signature = "(" + paramSig + ")" + NativeHelper.classToSig(source.getReturnType());

				MethodHandle handle = request.target;
				int targetParamCount = handle.type().parameterCount();
				int sourceParamCount = source.getParameterCount();

				// JNI 原生函数调用规范固定传入：(JNIEnv* env, jobject/jclass self, ...args)
				if (targetParamCount == sourceParamCount) {
					// 目标只接收实际业务参数，丢弃底层的 (env, self)
					handle = MethodHandles.dropArguments(handle, 0, MemorySegment.class, MemorySegment.class);
				} else if (targetParamCount == sourceParamCount + 1) {
					// 目标接收 (self, ...args)，丢弃底层 (env)
					handle = MethodHandles.dropArguments(handle, 0, MemorySegment.class);
				} else if (targetParamCount == sourceParamCount + 2) {
					// 目标显式接收 (env, self, ...args)
				} else {
					throw new IllegalArgumentException(
					 "Method parameter count mismatch for " + source.getName() +
					 ": target handle accepts " + targetParamCount + " params, but expected " +
					 sourceParamCount + " (args only), " + (sourceParamCount + 1) + " (self+args), or " +
					 (sourceParamCount + 2) + " (env+self+args)"
					);
				}

				FunctionDescriptor functionDescriptor = toFunctionDescriptor(handle.type());
				MemorySegment fnPtr = Linker.nativeLinker().upcallStub(
				 handle,
				 functionDescriptor,
				 stubArena
				);

				MemorySegment namePtr      = arena.allocateFrom(name);
				MemorySegment signaturePtr = arena.allocateFrom(signature);

				long offset = i * JNI_NATIVE_METHOD_LAYOUT.byteSize();
				nameVH.set(methods, offset, namePtr);
				signatureVH.set(methods, offset, signaturePtr);
				fnPtrVH.set(methods, offset, fnPtr);
				i++;
			}

			int res = (int) registerNativeMH.invokeExact(registerNativesFp, jniEnv.getJniEnvPointer(), jclassRef.ref(), methods, methodBinderRequests.size());
			if (res != 0) {
				throw new IllegalStateException("RegisterNatives failed with error code: " + res);
			}
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	private static FunctionDescriptor toFunctionDescriptor(MethodType methodType) {
		boolean        isVoid         = methodType.returnType().equals(void.class);
		Class<?>[]     parameterArray = methodType.parameterArray();
		MemoryLayout[] valueLayouts   = Arrays.stream(parameterArray)
		 .map(RegisterNative::toLayout)
		 .toArray(MemoryLayout[]::new);

		return isVoid ? FunctionDescriptor.ofVoid(valueLayouts)
		 : FunctionDescriptor.of(toLayout(methodType.returnType()), valueLayouts);
	}

	private static MemoryLayout toLayout(Class<?> c) {
		if (c == int.class) return ValueLayout.JAVA_INT;
		if (c == long.class) return ValueLayout.JAVA_LONG;
		if (c == short.class) return ValueLayout.JAVA_SHORT;
		if (c == char.class) return ValueLayout.JAVA_CHAR;
		if (c == float.class) return ValueLayout.JAVA_FLOAT;
		if (c == double.class) return ValueLayout.JAVA_DOUBLE;
		if (c == byte.class) return ValueLayout.JAVA_BYTE;
		if (c == boolean.class) return ValueLayout.JAVA_BOOLEAN;
		if (c == MemorySegment.class || MemorySegment.class.isAssignableFrom(c)) return ValueLayout.ADDRESS;

		throw new IllegalArgumentException(
		 "Unsupported carrier type for native layout: " + c +
		 ". In Panama upcall stubs, all JNI handles/pointers must be typed as MemorySegment.class, or primitive types for numbers/booleans."
		);
	}
}
