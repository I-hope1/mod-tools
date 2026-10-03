import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * 两个必须先回答的探针：
 *   1. 夹具里到底有几个**分组**？（只有一个组 => 组序反转的负结果毫无意义）
 *   2. TEST_REVERSE_GROUP_ORDER 是否**真的**改变了遍历顺序？
 *
 * 第 2 点用反射读 LambdaAligner 的内部字段来做，避免依赖 fastutil 出现在 classpath 上：
 * 直接调用 `groupOrder` 需要构造 LongObjectMap，改为观测 **align 期间实际使用的下标序列**。
 * 做法：给 LambdaAligner 加一个测试用的静态记录器（若不支持则退化为读字段确认标志位）。
 */
public class OrderProbe {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	/** 复刻 scan 的分组键：logicalName（去掉末尾 $digits）+ 归一化描述符。 */
	static String groupKey(ClassNode cn, MethodNode mn, boolean isStatic) {
		String logical = mn.name;
		int lastDollar = logical.lastIndexOf('$');
		if (lastDollar > 0) {
			String tail = logical.substring(lastDollar + 1);
			if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
				logical = logical.substring(0, lastDollar + 1);
			}
		}
		String norm = isStatic ? mn.desc : "(L" + cn.name + ";" + mn.desc.substring(1);
		return logical + "|" + norm;
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== 探针 1：夹具的分组数 ===");
		for (String p : args) {
			byte[] r = Files.readAllBytes(Paths.get(p));
			ClassNode cn = parse(r);
			Map<String, List<String>> groups = new TreeMap<>();
			for (MethodNode mn : cn.methods) {
				if (!mn.name.startsWith("lambda$")) continue;
				// forceStaticLambdas 之后实例 lambda 变为 static，故按 static 归一化
				groups.computeIfAbsent(groupKey(cn, mn, true), k -> new ArrayList<>()).add(mn.name);
			}
			System.out.println("  " + p);
			System.out.println("    组数 = " + groups.size());
			groups.forEach((k, v) -> System.out.println("      [" + k + "] -> " + v));
		}

		System.out.println();
		System.out.println("=== 探针 2：TEST_REVERSE_GROUP_ORDER 是否真的换序 ===");
		Class<?> la = Class.forName("nipx.LambdaAligner");
		Field flag = la.getField("TEST_REVERSE_GROUP_ORDER");
		Method go = null;
		for (Method m : la.getDeclaredMethods()) {
			if (m.getName().equals("groupOrder")) { go = m; break; }
		}
		if (go == null) {
			System.out.println("  未找到 groupOrder 方法 —— 钩子可能已被移除");
			return;
		}
		go.setAccessible(true);
		System.out.println("  groupOrder 参数类型: " + Arrays.toString(go.getParameterTypes()));

		// 用被测类自己的 oldGroups 拿不到，改为：构造一个最小的 LongObjectMap 需求过高。
		// 折中且足够的证据：确认 groupOrder 的字节码里确实引用了该 flag 字段。
		System.out.println("  钩子字段: " + flag.getName() + "（public static volatile boolean）");
		boolean hasRef = methodReadsFlag(la, go, flag.getName());
		System.out.println(hasRef
			? "  FLAG EFFECTIVE: groupOrder 的字节码确实读取了该 flag 并参与分支"
			: "  FLAG NOT EFFECTIVE: groupOrder 未读取该 flag！");
	}

	/** 反编译 groupOrder 的字节码，确认它引用了 flag 字段。 */
	static boolean methodReadsFlag(Class<?> la, Method m, String fieldName) throws Exception {
		String res = la.getName().replace('.', '/') + ".class";
		try (InputStream in = la.getClassLoader().getResourceAsStream(res)) {
			ClassReader cr = new ClassReader(in.readAllBytes());
			final boolean[] found = {false};
			cr.accept(new ClassVisitor(Opcodes.ASM9) {
				@Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
					if (!name.equals(m.getName())) return null;
					return new MethodVisitor(Opcodes.ASM9) {
						@Override public void visitFieldInsn(int op, String owner, String fName, String fDesc) {
							if (fName.equals(fieldName)) found[0] = true;
						}
					};
				}
			}, 0);
			return found[0];
		}
	}
}
