package hope.magic.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 负责在 Bootstrap ClassLoader 的 java.lang.invoke 包中定义 MagicBootLinker 原生直调桥接类。
 */
public class MagicBootLinker {
	private static volatile boolean initialized = false;

	public static synchronized void init() {
		if (initialized) return;
		try {
			try {
				Class.forName("java.lang.invoke.MagicBootLinker", false, null);
				initialized = true;
				return;
			} catch (ClassNotFoundException ignored) {
			}

			byte[] bytes = buildBootLinkerBytes();
			Magic.defineClassInInvokePackage(bytes);
			initialized = true;
		} catch (Throwable e) {
			throw new RuntimeException("Failed to initialize java.lang.invoke.MagicBootLinker", e);
		}
	}

	/**
	 * 构建包含原生 linkToSpecial / linkToStatic / linkToVirtual 指令的字节码 (纯 Java 标准库实现，零外部依赖)
	 */
	public static byte[] buildBootLinkerBytes() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(baos);

			out.writeInt(0xCAFEBABE);
			out.writeShort(0);
			out.writeShort(52); // Java 8

			// 常量池
			ByteArrayOutputStream cpStream = new ByteArrayOutputStream();
			DataOutputStream cp = new DataOutputStream(cpStream);
			int cpCount = 1;

			// 1: UTF8 "java/lang/invoke/MagicBootLinker"
			cp.writeByte(1); cp.writeUTF("java/lang/invoke/MagicBootLinker"); cpCount++;
			// 2: Class 1
			cp.writeByte(7); cp.writeShort(1); cpCount++;
			// 3: UTF8 "java/lang/Object"
			cp.writeByte(1); cp.writeUTF("java/lang/Object"); cpCount++;
			// 4: Class 3
			cp.writeByte(7); cp.writeShort(3); cpCount++;
			// 5: UTF8 "<init>"
			cp.writeByte(1); cp.writeUTF("<init>"); cpCount++;
			// 6: UTF8 "()V"
			cp.writeByte(1); cp.writeUTF("()V"); cpCount++;
			// 7: NameAndType 5, 6
			cp.writeByte(12); cp.writeShort(5); cp.writeShort(6); cpCount++;
			// 8: Methodref 4, 7 (Object.<init>)
			cp.writeByte(10); cp.writeShort(4); cp.writeShort(7); cpCount++;

			// 9: UTF8 "Code"
			cp.writeByte(1); cp.writeUTF("Code"); cpCount++;
			// 10: UTF8 "java/lang/invoke/MemberName"
			cp.writeByte(1); cp.writeUTF("java/lang/invoke/MemberName"); cpCount++;
			// 11: Class 10
			cp.writeByte(7); cp.writeShort(10); cpCount++;
			// 12: UTF8 "java/lang/invoke/MethodHandle"
			cp.writeByte(1); cp.writeUTF("java/lang/invoke/MethodHandle"); cpCount++;
			// 13: Class 12
			cp.writeByte(7); cp.writeShort(12); cpCount++;

			// 14: UTF8 "linkToSpecial"
			cp.writeByte(1); cp.writeUTF("linkToSpecial"); cpCount++;
			// 15: UTF8 "(Ljava/lang/Object;IILjava/lang/invoke/MemberName;)I"
			cp.writeByte(1); cp.writeUTF("(Ljava/lang/Object;IILjava/lang/invoke/MemberName;)I"); cpCount++;
			// 16: NameAndType 14, 15
			cp.writeByte(12); cp.writeShort(14); cp.writeShort(15); cpCount++;
			// 17: Methodref 13, 16 (MethodHandle.linkToSpecial)
			cp.writeByte(10); cp.writeShort(13); cp.writeShort(16); cpCount++;

			// 18: UTF8 "linkToSpecial_II_I"
			cp.writeByte(1); cp.writeUTF("linkToSpecial_II_I"); cpCount++;
			// 19: UTF8 "(Ljava/lang/Object;IILjava/lang/Object;)I"
			cp.writeByte(1); cp.writeUTF("(Ljava/lang/Object;IILjava/lang/Object;)I"); cpCount++;

			// 20: UTF8 "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/invoke/MemberName;)Ljava/lang/Object;"
			cp.writeByte(1); cp.writeUTF("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/invoke/MemberName;)Ljava/lang/Object;"); cpCount++;
			// 21: NameAndType 14, 20
			cp.writeByte(12); cp.writeShort(14); cp.writeShort(20); cpCount++;
			// 22: Methodref 13, 21 (MethodHandle.linkToSpecial)
			cp.writeByte(10); cp.writeShort(13); cp.writeShort(21); cpCount++;

			// 23: UTF8 "linkToSpecial_L_L"
			cp.writeByte(1); cp.writeUTF("linkToSpecial_L_L"); cpCount++;
			// 24: UTF8 "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
			cp.writeByte(1); cp.writeUTF("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"); cpCount++;

			// 25: UTF8 "linkToStatic"
			cp.writeByte(1); cp.writeUTF("linkToStatic"); cpCount++;
			// 26: UTF8 "(Ljava/lang/Object;Ljava/lang/invoke/MemberName;)Ljava/lang/Object;"
			cp.writeByte(1); cp.writeUTF("(Ljava/lang/Object;Ljava/lang/invoke/MemberName;)Ljava/lang/Object;"); cpCount++;
			// 27: NameAndType 25, 26
			cp.writeByte(12); cp.writeShort(25); cp.writeShort(26); cpCount++;
			// 28: Methodref 13, 27 (MethodHandle.linkToStatic)
			cp.writeByte(10); cp.writeShort(13); cp.writeShort(27); cpCount++;

			// 29: UTF8 "linkToStatic_L_L"
			cp.writeByte(1); cp.writeUTF("linkToStatic_L_L"); cpCount++;
			// 30: UTF8 "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
			cp.writeByte(1); cp.writeUTF("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"); cpCount++;

			// 31: UTF8 "(Ljava/lang/Object;Ljava/lang/invoke/MemberName;)V"
			cp.writeByte(1); cp.writeUTF("(Ljava/lang/Object;Ljava/lang/invoke/MemberName;)V"); cpCount++;
			// 32: NameAndType 14, 31
			cp.writeByte(12); cp.writeShort(14); cp.writeShort(31); cpCount++;
			// 33: Methodref 13, 32 (MethodHandle.linkToSpecial)
			cp.writeByte(10); cp.writeShort(13); cp.writeShort(32); cpCount++;

			// 34: UTF8 "linkToSpecial_V"
			cp.writeByte(1); cp.writeUTF("linkToSpecial_V"); cpCount++;
			// 35: UTF8 "(Ljava/lang/Object;Ljava/lang/Object;)V"
			cp.writeByte(1); cp.writeUTF("(Ljava/lang/Object;Ljava/lang/Object;)V"); cpCount++;

			// 36: UTF8 "linkToVirtual"
			cp.writeByte(1); cp.writeUTF("linkToVirtual"); cpCount++;
			// 37: NameAndType 36, 20
			cp.writeByte(12); cp.writeShort(36); cp.writeShort(20); cpCount++;
			// 38: Methodref 13, 37 (MethodHandle.linkToVirtual)
			cp.writeByte(10); cp.writeShort(13); cp.writeShort(37); cpCount++;

			// 39: UTF8 "linkToVirtual_L_L"
			cp.writeByte(1); cp.writeUTF("linkToVirtual_L_L"); cpCount++;

			// 40: NameAndType 36, 15
			cp.writeByte(12); cp.writeShort(36); cp.writeShort(15); cpCount++;
			// 41: Methodref 13, 40 (MethodHandle.linkToVirtual)
			cp.writeByte(10); cp.writeShort(13); cp.writeShort(40); cpCount++;

			// 42: UTF8 "linkToVirtual_II_I"
			cp.writeByte(1); cp.writeUTF("linkToVirtual_II_I"); cpCount++;

			out.writeShort(cpCount);
			out.write(cpStream.toByteArray());

			out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
			out.writeShort(2);      // this_class
			out.writeShort(4);      // super_class
			out.writeShort(0);      // interfaces count
			out.writeShort(0);      // fields count

			// 7 methods (<init>, linkToSpecial_II_I, linkToSpecial_L_L, linkToStatic_L_L, linkToSpecial_V, linkToVirtual_L_L, linkToVirtual_II_I)
			out.writeShort(7);

			// Method 1: <init>
			writeMethod(out, 0x0001, 5, 6, 9, new byte[]{0x2A, (byte) 0xB7, 0, 8, (byte) 0xB1}, 1, 1);

			// Method 2: linkToSpecial_II_I: aload_0, iload_1, iload_2, aload_3, checkcast #11, invokestatic #17, ireturn
			writeMethod(out, 0x0009, 18, 19, 9, new byte[]{
				0x2A, 0x1B, 0x1C, 0x2D, (byte) 0xC0, 0, 11, (byte) 0xB8, 0, 17, (byte) 0xAC
			}, 4, 4);

			// Method 3: linkToSpecial_L_L: aload_0, aload_1, aload_2, checkcast #11, invokestatic #22, areturn
			writeMethod(out, 0x0009, 23, 24, 9, new byte[]{
				0x2A, 0x2B, 0x2C, (byte) 0xC0, 0, 11, (byte) 0xB8, 0, 22, (byte) 0xB0
			}, 3, 3);

			// Method 4: linkToStatic_L_L: aload_0, aload_1, checkcast #11, invokestatic #28, areturn
			writeMethod(out, 0x0009, 29, 30, 9, new byte[]{
				0x2A, 0x2B, (byte) 0xC0, 0, 11, (byte) 0xB8, 0, 28, (byte) 0xB0
			}, 2, 2);

			// Method 5: linkToSpecial_V: aload_0, aload_1, checkcast #11, invokestatic #33, return
			writeMethod(out, 0x0009, 34, 35, 9, new byte[]{
				0x2A, 0x2B, (byte) 0xC0, 0, 11, (byte) 0xB8, 0, 33, (byte) 0xB1
			}, 2, 2);

			// Method 6: linkToVirtual_L_L: aload_0, aload_1, aload_2, checkcast #11, invokestatic #38, areturn
			writeMethod(out, 0x0009, 39, 24, 9, new byte[]{
				0x2A, 0x2B, 0x2C, (byte) 0xC0, 0, 11, (byte) 0xB8, 0, 38, (byte) 0xB0
			}, 3, 3);

			// Method 7: linkToVirtual_II_I: aload_0, iload_1, iload_2, aload_3, checkcast #11, invokestatic #41, ireturn
			writeMethod(out, 0x0009, 42, 19, 9, new byte[]{
				0x2A, 0x1B, 0x1C, 0x2D, (byte) 0xC0, 0, 11, (byte) 0xB8, 0, 41, (byte) 0xAC
			}, 4, 4);

			out.writeShort(0); // attributes count

			out.flush();
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void writeMethod(
		DataOutputStream out,
		int access,
		int nameIdx,
		int descIdx,
		int codeAttrIdx,
		byte[] code,
		int maxStack,
		int maxLocals
	) throws IOException {
		out.writeShort(access);
		out.writeShort(nameIdx);
		out.writeShort(descIdx);
		out.writeShort(1); // 1 attribute (Code)

		out.writeShort(codeAttrIdx);
		out.writeInt(12 + code.length);
		out.writeShort(maxStack);
		out.writeShort(maxLocals);
		out.writeInt(code.length);
		out.write(code);
		out.writeShort(0); // 0 exception handlers
		out.writeShort(0); // 0 code attributes
	}
}
