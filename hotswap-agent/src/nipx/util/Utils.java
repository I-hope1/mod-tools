package nipx.util;

import org.objectweb.asm.ClassReader;

import java.io.*;
import java.nio.file.*;

import static nipx.HotSwapAgent.*;

public class Utils {
	/**
	 * 计算复合 Key：将逻辑名 Hash 和描述符 Hash 压缩进一个 long
	 * 解决了 String 拼接产生的 char[] 拷贝和 StringBuilder 垃圾
	 */
	public static long compositeHash(String logicalName, String desc) {
		long crc = CRC64.init();
		crc = CRC64.updateStringUTF16(crc, logicalName);
		crc = CRC64.updateStringUTF16(crc, desc);
		return CRC64.finish(crc);
	}
	public static long hash(String itf) {
		return CRC64.hashString(itf);
	}

	public static String getClassName(Path classFile) {
		if (DEBUG) log("[PARSE] Parsing class file: " + classFile);
		return getClassNameFast(classFile);
	}
	/** 使用 ASM 获取 class 文件的逻辑名 */
	private static String getClassNameASM(Path classFile) {
		try (InputStream is = Files.newInputStream(classFile)) {
			ClassReader cr = new ClassReader(is);
			return cr.getClassName().replace('/', '.');
		} catch (IOException | IllegalArgumentException e) {
			return null; // 不是合法的 class 文件
		}
	}
	public static String getClassNameASM(byte[] bytes) {
		try (InputStream is = new ByteArrayInputStream(bytes)) {
			ClassReader cr = new ClassReader(is);
			return cr.getClassName().replace('/', '.');
		} catch (IOException | IllegalArgumentException e) {
			return null; // 不是合法的 class 文件
		}
	}

	/**
	 * 快速从.class文件中提取类名。
	 * @param classFile .class文件的路径
	 * @return 类的全限定名（以点号分隔），如果解析失败则返回null
	 */
	private static String getClassNameFast(Path classFile) {
		try (DataInputStream dis = new DataInputStream(Files.newInputStream(classFile))) {
			// 验证魔数是否为0xCAFEBABE
			if (dis.readInt() != 0xCAFEBABE) return null;
			dis.readInt(); // 跳过版本信息

			// 读取常量池大小并初始化相关数组
			int      cpCount = dis.readUnsignedShort();
			String[] strings = new String[cpCount];
			int[]    classes = new int[cpCount];

			// 遍历常量池，根据标签类型处理不同数据
			for (int i = 1; i < cpCount; i++) {
				int tag = dis.readUnsignedByte();
				switch (tag) {
					case 1:  // UTF8字符串
						strings[i] = dis.readUTF();
						break;
					case 7:  // 类引用
						classes[i] = dis.readUnsignedShort();
						break;
					case 5:
					case 6: // Long或Double（占用两个槽位）
						dis.skipBytes(8);
						i++;
						break;
					case 3:
					case 4:
						dis.skipBytes(4); // 跳过u4类型数据
						break;
					case 8:
					case 16:
						dis.skipBytes(2); // 跳过u2类型数据
						break;
					case 9:
					case 10:
					case 11:
					case 12:
					case 18:
						dis.skipBytes(4); // 跳过两个u2类型数据
						break;
					case 15: // MethodHandle
						dis.skipBytes(3);
						break;
					default:
						return null; // 遇到未知标签，视为非法格式
				}
			}

			dis.skipBytes(2); // 跳过访问标志
			int thisClassIdx = dis.readUnsignedShort(); // 获取当前类在常量池中的索引
			// 返回类名，并将斜杠替换为点号
			return strings[classes[thisClassIdx]].replace('/', '.');
		} catch (Exception e) {
			return null; // 发生异常时返回null
		}
	}

}
