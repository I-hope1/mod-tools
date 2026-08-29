package hope.magic.runtime;

import sun.misc.Unsafe;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Magic {
	public static final Unsafe unsafe = getUnsafe();
	public static final Lookup lookup = getLookup();

	private static volatile boolean installed = false;

	/**
	 * 安装 MagicAccessor 运行期基础设施。
	 * 在调用任何 {@code @HField} 或 {@code @HMethod} 对应的方法前调用一次即可。
	 */
	public static synchronized void install() {
		if (installed) return;
		try {
			ModuleOpen.openModule(Object.class.getModule(), "jdk.internal.misc");
			ModuleOpen.openModule(Object.class.getModule(), "jdk.internal.reflect");

			// 1. 定义 jdk.internal.reflect.MagicAccessorImpl_PUBLIC
			byte[] magicPublicBytes = new byte[]{
				-54, -2, -70, -66, 0, 0, 0, 52, 0, 13, 1, 0, 45, 106, 100, 107, 47, 105, 110, 116, 101, 114, 110, 97, 108, 47, 114, 101, 102, 108, 101, 99, 116, 47, 77, 97, 103, 105, 99, 65, 99, 99, 101, 115, 115, 111, 114, 73, 109, 112, 108, 95, 80, 85, 66, 76, 73, 67, 7, 0, 1, 1, 0, 38, 106, 100, 107, 47, 105, 110, 116, 101, 114, 110, 97, 108, 47, 114, 101, 102, 108, 101, 99, 116, 47, 77, 97, 103, 105, 99, 65, 99, 99, 101, 115, 115, 111, 114, 73, 109, 112, 108, 7, 0, 3, 1, 0, 13, 95, 95, 66, 89, 84, 69, 95, 67, 108, 97, 115, 115, 48, 1, 0, 6, 60, 105, 110, 105, 116, 62, 1, 0, 3, 40, 41, 86, 12, 0, 6, 0, 7, 10, 0, 4, 0, 8, 1, 0, 4, 67, 111, 100, 101, 1, 0, 13, 83, 116, 97, 99, 107, 77, 97, 112, 84, 97, 98, 108, 101, 1, 0, 10, 83, 111, 117, 114, 99, 101, 70, 105, 108, 101, 0, 1, 0, 2, 0, 4, 0, 0, 0, 0, 0, 1, 0, 1, 0, 6, 0, 7, 0, 1, 0, 10, 0, 0, 0, 25, 0, 1, 0, 1, 0, 0, 0, 5, 42, -73, 0, 9, -79, 0, 0, 0, 1, 0, 11, 0, 0, 0, 2, 0, 0, 0, 1, 0, 12, 0, 0, 0, 2, 0, 5
			};
			defineClass(null, magicPublicBytes);

			// 2. 定义 hope.magic.runtime.MAGICIMPL
			byte[] magicImplBytes = buildMagicSubclassBytes(
				"hope/magic/runtime/MAGICIMPL",
				"jdk/internal/reflect/MagicAccessorImpl_PUBLIC"
			);
			defineClass(null, magicImplBytes);

			// 3. 兼顾 apzmagic.MAGICIMPL
			byte[] apzMagicImplBytes = buildMagicSubclassBytes(
				"apzmagic/MAGICIMPL",
				"jdk/internal/reflect/MagicAccessorImpl_PUBLIC"
			);
			defineClass(null, apzMagicImplBytes);

			installed = true;
		} catch (Throwable e) {
			throw new RuntimeException("Failed to install MagicAccessor runtime", e);
		}
	}

	public static Class<?> defineClass(ClassLoader loader, byte[] bytes) {
		try {
			return jdk.internal.misc.Unsafe.getUnsafe().defineClass(null, bytes, 0, bytes.length, loader, null);
		} catch (Throwable t1) {
			try {
				Method defineClassMethod = Unsafe.class.getDeclaredMethod(
					"defineClass", String.class, byte[].class, int.class, int.class, ClassLoader.class, java.security.ProtectionDomain.class
				);
				defineClassMethod.setAccessible(true);
				return (Class<?>) defineClassMethod.invoke(unsafe, null, bytes, 0, bytes.length, loader, null);
			} catch (Throwable t2) {
				throw new RuntimeException("Failed to define class into JVM", t1);
			}
		}
	}

	/**
	 * 构建继承自指定父类的简单公共类字节码
	 */
	public static byte[] buildMagicSubclassBytes(String internalName, String superInternalName) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(baos);

			// Magic header 0xCAFEBABE, version 52.0 (Java 8)
			out.writeInt(0xCAFEBABE);
			out.writeShort(0); // minor
			out.writeShort(52); // major (Java 8)

			// Constant Pool count = 13
			out.writeShort(13);

			// 1: Utf8 className
			out.writeByte(1);
			out.writeUTF(internalName);
			// 2: Class #1
			out.writeByte(7);
			out.writeShort(1);

			// 3: Utf8 superClassName
			out.writeByte(1);
			out.writeUTF(superInternalName);
			// 4: Class #3
			out.writeByte(7);
			out.writeShort(3);

			// 5: Utf8 __BYTE_Class0
			out.writeByte(1);
			out.writeUTF("__BYTE_Class0");

			// 6: Utf8 <init>
			out.writeByte(1);
			out.writeUTF("<init>");

			// 7: Utf8 ()V
			out.writeByte(1);
			out.writeUTF("()V");

			// 8: NameAndType #6:#7
			out.writeByte(12);
			out.writeShort(6);
			out.writeShort(7);

			// 9: Methodref #4.#8
			out.writeByte(10);
			out.writeShort(4);
			out.writeShort(8);

			// 10: Utf8 Code
			out.writeByte(1);
			out.writeUTF("Code");

			// 11: Utf8 StackMapTable
			out.writeByte(1);
			out.writeUTF("StackMapTable");

			// 12: Utf8 SourceFile
			out.writeByte(1);
			out.writeUTF("SourceFile");

			// access_flags: ACC_PUBLIC | ACC_SUPER = 0x0021
			out.writeShort(0x0021);
			out.writeShort(2); // this_class #2
			out.writeShort(4); // super_class #4
			out.writeShort(0); // interfaces count
			out.writeShort(0); // fields count

			// methods count = 1 (<init>)
			out.writeShort(1);
			out.writeShort(0x0001); // ACC_PUBLIC
			out.writeShort(6);      // name_index <init>
			out.writeShort(7);      // descriptor_index ()V
			out.writeShort(1);      // attributes count

			// Code attribute
			out.writeShort(10);     // attribute_name_index "Code"
			out.writeInt(17);       // attribute_length
			out.writeShort(1);      // max_stack = 1
			out.writeShort(1);      // max_locals = 1
			out.writeInt(5);        // code_length = 5
			out.writeByte(0x2A);    // aload_0
			out.writeByte(0xB7);    // invokespecial
			out.writeShort(9);      // methodref #9
			out.writeByte(0xB1);    // return
			out.writeShort(0);      // exception_table_length
			out.writeShort(0);      // attributes count

			// class attributes count = 1 (SourceFile)
			out.writeShort(1);
			out.writeShort(12);     // "SourceFile"
			out.writeInt(2);        // length = 2
			out.writeShort(5);      // "__BYTE_Class0"

			out.flush();
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Unsafe getUnsafe() {
		try {
			Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			return (Unsafe) theUnsafe.get(null);
		} catch (Throwable e) {
			try {
				Field theUnsafe = Unsafe.class.getDeclaredField("theInternalUnsafe");
				theUnsafe.setAccessible(true);
				return (Unsafe) theUnsafe.get(null);
			} catch (Throwable ex) {
				throw new RuntimeException(ex);
			}
		}
	}

	private static Lookup getLookup() {
		try {
			Field implLookupField = Lookup.class.getDeclaredField("IMPL_LOOKUP");
			long offset = unsafe.staticFieldOffset(implLookupField);
			return (Lookup) unsafe.getObject(Lookup.class, offset);
		} catch (Throwable e) {
			try {
				Lookup baseLookup = MethodHandles.lookup();
				Field modesField = Lookup.class.getDeclaredField("allowedModes");
				long offset = unsafe.objectFieldOffset(modesField);
				unsafe.putInt(baseLookup, offset, -1);
				return baseLookup;
			} catch (Throwable ex) {
				throw new RuntimeException(ex);
			}
		}
	}
}
