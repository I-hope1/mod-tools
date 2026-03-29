package nipx.jni.helper;

import nipx.jni.JNIEnv;
import sun.misc.Unsafe;

import java.lang.foreign.Arena;
import java.lang.invoke.*;
import java.lang.reflect.*;

public interface MasterKey {

	MasterKey INSTANCE = Runtime.version().feature() >= 22 ? new MasterKeyPanamaImpl() : new MasterKeyUnsafeImpl();

	MethodHandle openTheDoor(Method method);

	MethodHandle openTheDoor(Constructor<?> ctor);

	VarHandle openTheDoor(Field field);

	MethodHandles.Lookup getTrustedLookup();

	static boolean isPanamaBackend() {
		return INSTANCE instanceof MasterKeyPanamaImpl;
	}

	class MasterKeyUnsafeImpl implements MasterKey {

		public static MethodHandles.Lookup lookup;

		static {
			try {
				Field field = Unsafe.class.getDeclaredField("theUnsafe");
				field.setAccessible(true);
				Unsafe unsafe          = (Unsafe) field.get(null);
				Field  implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
				Object base            = unsafe.staticFieldBase(implLookupField);
				long   fieldOffset     = unsafe.staticFieldOffset(implLookupField);
				lookup = ((MethodHandles.Lookup) unsafe.getObject(base, fieldOffset));
				System.out.println("unsafe impl");
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		public MethodHandle openTheDoor(Method method) {
			return NativeHelper.throwable(() -> lookup.unreflect(method));
		}

		public MethodHandle openTheDoor(Constructor ctor) {
			return NativeHelper.throwable(() -> lookup.unreflectConstructor(ctor));
		}

		public VarHandle openTheDoor(Field field) {
			return NativeHelper.throwable(() -> lookup.unreflectVarHandle(field));
		}

		@Override
		public MethodHandles.Lookup getTrustedLookup() {
			return lookup;
		}
	}

	class MasterKeyPanamaImpl implements MasterKey {
		public static MethodHandles.Lookup lookup;

		static {
			try (Arena arena = Arena.ofConfined()) {
				JNIEnv    jniEnv     = new JNIEnv(arena);
				GlobalRef implLookup = jniEnv.GetStaticFieldByName(MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP"));
				Object    o          = jniEnv.jObjectToJavaObject(implLookup.ref());
				lookup = (MethodHandles.Lookup) o;
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}
		}

		public MethodHandle openTheDoor(Method method) {
			return NativeHelper.throwable(() -> lookup.unreflect(method));
		}

		public MethodHandle openTheDoor(Constructor ctor) {
			return NativeHelper.throwable(() -> lookup.unreflectConstructor(ctor));
		}

		public VarHandle openTheDoor(Field field) {
			return NativeHelper.throwable(() -> lookup.unreflectVarHandle(field));
		}

		@Override
		public MethodHandles.Lookup getTrustedLookup() {
			return lookup;
		}

	}


}
