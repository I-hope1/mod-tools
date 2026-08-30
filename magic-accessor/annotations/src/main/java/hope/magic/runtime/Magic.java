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
			ModuleOpen.openModule(Object.class.getModule(), "java.lang.invoke");

			// 尝试定义 MagicAccessorImpl 基础特权类 (适用于 JDK <= 21 的 MAGIC_ACCESSOR 模式)
			try {
				try {
					Class.forName("jdk.internal.reflect.MagicAccessorImpl_PUBLIC", false, null);
				} catch (ClassNotFoundException e) {
					byte[] magicPublicBytes = new byte[]{
						-54, -2, -70, -66, 0, 0, 0, 52, 0, 13, 1, 0, 45, 106, 100, 107, 47, 105, 110, 116, 101, 114, 110, 97, 108, 47, 114, 101, 102, 108, 101, 99, 116, 47, 77, 97, 103, 105, 99, 65, 99, 99, 101, 115, 115, 111, 114, 73, 109, 112, 108, 95, 80, 85, 66, 76, 73, 67, 7, 0, 1, 1, 0, 38, 106, 100, 107, 47, 105, 110, 116, 101, 114, 110, 97, 108, 47, 114, 101, 102, 108, 101, 99, 116, 47, 77, 97, 103, 105, 99, 65, 99, 99, 101, 115, 115, 111, 114, 73, 109, 112, 108, 7, 0, 3, 1, 0, 13, 95, 95, 66, 89, 84, 69, 95, 67, 108, 97, 115, 115, 48, 1, 0, 6, 60, 105, 110, 105, 116, 62, 1, 0, 3, 40, 41, 86, 12, 0, 6, 0, 7, 10, 0, 4, 0, 8, 1, 0, 4, 67, 111, 100, 101, 1, 0, 13, 83, 116, 97, 99, 107, 77, 97, 112, 84, 97, 98, 108, 101, 1, 0, 10, 83, 111, 117, 114, 99, 101, 70, 105, 108, 101, 0, 1, 0, 2, 0, 4, 0, 0, 0, 0, 0, 1, 0, 1, 0, 6, 0, 7, 0, 1, 0, 10, 0, 0, 0, 25, 0, 1, 0, 1, 0, 0, 0, 5, 42, -73, 0, 9, -79, 0, 0, 0, 1, 0, 11, 0, 0, 0, 2, 0, 0, 0, 1, 0, 12, 0, 0, 0, 2, 0, 5
					};
					defineClass(null, magicPublicBytes);
				}

				try {
					Class.forName("hope.magic.runtime.MAGICIMPL", false, null);
				} catch (ClassNotFoundException e) {
					byte[] magicImplBytes = buildMagicSubclassBytes(
						"hope/magic/runtime/MAGICIMPL",
						"jdk/internal/reflect/MagicAccessorImpl_PUBLIC"
					);
					defineClass(null, magicImplBytes);
				}

				try {
					Class.forName("apzmagic.MAGICIMPL", false, null);
				} catch (ClassNotFoundException e) {
					byte[] apzMagicImplBytes = buildMagicSubclassBytes(
						"apzmagic/MAGICIMPL",
						"jdk/internal/reflect/MagicAccessorImpl_PUBLIC"
					);
					defineClass(null, apzMagicImplBytes);
				}
			} catch (Throwable ignored) {
			}

			installed = true;
		} catch (Throwable e) {
			throw new RuntimeException("Failed to install MagicAccessor runtime", e);
		}
	}

	private static final java.util.Set<String> INSTALLED_BRIDGES = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

	/**
	 * 安装指定的 Bridge 桥接类到 Bootstrap ClassLoader。
	 * 内部采用 DCL (双重检查锁定) 与线程安全的内存缓存，保证严格的线程安全与幂等性。
	 */
	public static void installBridge(String className, String base64) {
		if (className == null || base64 == null || base64.isEmpty()) return;
		if (INSTALLED_BRIDGES.contains(className)) return; // 极速无锁快路径

		synchronized (Magic.class) {
			if (INSTALLED_BRIDGES.contains(className)) return;

			try {
				Class.forName(className, false, null);
				INSTALLED_BRIDGES.add(className);
				return; // 已经在 Bootstrap 中定义过，直接返回
			} catch (ClassNotFoundException ignored) {
			}

			try {
				byte[] bytes = java.util.Base64.getDecoder().decode(base64);
				defineClass(null, bytes);
				INSTALLED_BRIDGES.add(className);
			} catch (Throwable t) {
				try {
					Class.forName(className, false, null);
					INSTALLED_BRIDGES.add(className);
				} catch (Throwable ignored) {
					throw new RuntimeException("Failed to install bridge " + className, t);
				}
			}
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

			out.writeInt(0xCAFEBABE);
			out.writeShort(0);
			out.writeShort(52); // Java 8

			out.writeShort(13);

			out.writeByte(1);
			out.writeUTF(internalName);
			out.writeByte(7);
			out.writeShort(1);

			out.writeByte(1);
			out.writeUTF(superInternalName);
			out.writeByte(7);
			out.writeShort(3);

			out.writeByte(1);
			out.writeUTF("__BYTE_Class0");

			out.writeByte(1);
			out.writeUTF("<init>");

			out.writeByte(1);
			out.writeUTF("()V");

			out.writeByte(12);
			out.writeShort(6);
			out.writeShort(7);

			out.writeByte(10);
			out.writeShort(4);
			out.writeShort(8);

			out.writeByte(1);
			out.writeUTF("Code");

			out.writeByte(1);
			out.writeUTF("StackMapTable");

			out.writeByte(1);
			out.writeUTF("SourceFile");

			out.writeShort(0x0021);
			out.writeShort(2);
			out.writeShort(4);
			out.writeShort(0);
			out.writeShort(0);

			out.writeShort(1);
			out.writeShort(0x0001);
			out.writeShort(6);
			out.writeShort(7);
			out.writeShort(1);

			out.writeShort(10);
			out.writeInt(17);
			out.writeShort(1);
			out.writeShort(1);
			out.writeInt(5);
			out.writeByte(0x2A); // aload_0
			out.writeByte(0xB7); // invokespecial
			out.writeShort(9);
			out.writeByte(0xB1); // return
			out.writeShort(0);
			out.writeShort(0);

			out.writeShort(1);
			out.writeShort(12);
			out.writeInt(2);
			out.writeShort(5);

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
