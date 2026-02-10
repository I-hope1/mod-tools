package nipx;

import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import jdk.internal.org.objectweb.asm.util.*;

import java.io.*;
import java.util.*;

final class ClassDiffUtil {

	private ClassDiffUtil() { }

	/* =========================
	 * 对外 API
	 * ========================= */

	// 现在输入必须是两份字节码
	static ClassDiff diff(byte[] oldBytes, byte[] newBytes) {
		ClassNode oldNode = parse(oldBytes);
		ClassNode newNode = parse(newBytes);
		return diff(oldNode, newNode);
	}
	private static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		// 关键：不使用 SKIP_CODE，否则无法检测方法体
		// SKIP_DEBUG 可以忽略行号变化，这对于 Diff 逻辑变更很有用
		ClassReader cr = new ClassReader(bytes);
		cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
	}

	/* =========================
	 * 数据结构
	 * ========================= */

	static final class ClassStructure {
		String         className;
		Set<FieldSig>  fields       = new HashSet<>();
		Set<MethodSig> methods      = new HashSet<>();
		Set<MethodSig> constructors = new HashSet<>();
	}

	static final class ClassDiff {
		final List<String> addedFields   = new ArrayList<>();
		final List<String> removedFields = new ArrayList<>();

		final List<String> addedMethods    = new ArrayList<>();
		final List<String> removedMethods  = new ArrayList<>();
		final List<String> modifiedMethods = new ArrayList<>();
		boolean hasChange() {
			return !addedFields.isEmpty() || !removedFields.isEmpty() ||
			       !addedMethods.isEmpty() || !removedMethods.isEmpty() ||
			       !modifiedMethods.isEmpty();
		}
	}

	static final class FieldSig {
		private final String name;
		private final String desc;
		private final int    access;
		FieldSig(String name, String desc, int access) {
			this.name = name;
			this.desc = desc;
			this.access = access;
		}
		public String name() { return name; }
		public String desc() { return desc; }
		public int access() { return access; }
		@Override
		public boolean equals(Object obj) {
			if (obj == this) return true;
			if (obj == null || obj.getClass() != this.getClass()) return false;
			var that = (FieldSig) obj;
			return Objects.equals(this.name, that.name) &&
			       Objects.equals(this.desc, that.desc) &&
			       this.access == that.access;
		}
		@Override
		public int hashCode() {
			return Objects.hash(name, desc, access);
		}
		@Override
		public String toString() {
			return "FieldSig[" +
			       "name=" + name + ", " +
			       "desc=" + desc + ", " +
			       "access=" + access + ']';
		}
	}

	static final class MethodSig {
		private final String name;
		private final String desc;
		private final int    access;
		MethodSig(String name, String desc, int access) {
			this.name = name;
			this.desc = desc;
			this.access = access;
		}
		public String name() { return name; }
		public String desc() { return desc; }
		public int access() { return access; }
		@Override
		public boolean equals(Object obj) {
			if (obj == this) return true;
			if (obj == null || obj.getClass() != this.getClass()) return false;
			var that = (MethodSig) obj;
			return Objects.equals(this.name, that.name) &&
			       Objects.equals(this.desc, that.desc) &&
			       this.access == that.access;
		}
		@Override
		public int hashCode() {
			return Objects.hash(name, desc, access);
		}
		@Override
		public String toString() {
			return "MethodSig[" +
			       "name=" + name + ", " +
			       "desc=" + desc + ", " +
			       "access=" + access + ']';
		}
	}

	/* =========================
	 * Diff
	 * ========================= */
	private static ClassDiff diff(ClassNode oldC, ClassNode newC) {
		ClassDiff d = new ClassDiff();

		// 1. Fields
		Set<String> oldFields = new HashSet<>();
		for (FieldNode f : oldC.fields) oldFields.add(key(f));
		Set<String> newFields = new HashSet<>();
		for (FieldNode f : newC.fields) newFields.add(key(f));

		for (String f : newFields) if (!oldFields.contains(f)) d.addedFields.add(f);
		for (String f : oldFields) if (!newFields.contains(f)) d.removedFields.add(f);

		// 2. Methods
		Map<String, MethodNode> oldMethods = new HashMap<>();
		for (MethodNode m : oldC.methods) oldMethods.put(key(m), m);

		Map<String, MethodNode> newMethods = new HashMap<>();
		for (MethodNode m : newC.methods) newMethods.put(key(m), m);

		// 检测新增和修改
		for (Map.Entry<String, MethodNode> entry : newMethods.entrySet()) {
			String     key  = entry.getKey();
			MethodNode newM = entry.getValue();
			MethodNode oldM = oldMethods.get(key);

			if (oldM == null) {
				d.addedMethods.add(key);
			} else {
				// 深度对比方法体
				if (isCodeChanged(oldM, newM)) {
					d.modifiedMethods.add(key);
				}
			}
		}

		// 检测删除
		for (String key : oldMethods.keySet()) {
			if (!newMethods.containsKey(key)) {
				d.removedMethods.add(key);
			}
		}

		return d;
	}

	private static String key(FieldNode f) { return f.name + " " + f.desc; }
	private static String key(MethodNode m) { return m.name + m.desc; }

	/**
	 * 核心：对比两个方法体逻辑是否一致
	 */
	private static boolean isCodeChanged(MethodNode m1, MethodNode m2) {
		// 1. 简单检查指令数量
		if (m1.instructions.size() != m2.instructions.size()) return true;

		// 2. 规范化为文本进行对比
		// 这种方式能屏蔽 Label 对象实例不同带来的干扰，也能屏蔽常量池索引差异
		String t1 = textify(m1);
		String t2 = textify(m2);

		return !t1.equals(t2);
	}

	private static String textify(MethodNode mn) {
		// 使用 ASM 内置的 Textifier 打印指令
		Textifier          printer = new Textifier();
		TraceMethodVisitor mp      = new TraceMethodVisitor(printer);
		mn.accept(mp);

		StringWriter sw = new StringWriter();
		printer.print(new PrintWriter(sw));
		return sw.toString();
	}

	/* =========================
	 * 日志
	 * ========================= */

	static void logDiff(String className, ClassDiff diff) {
		if (!diff.hasChange()) return;

		System.out.println("[NIPX] [DIFF] " + className);

		diff.addedFields.forEach(f -> System.out.println("  + field: " + f));
		diff.removedFields.forEach(f -> System.out.println("  - field: " + f));

		diff.addedMethods.forEach(m -> System.out.println("  + method: " + m));
		diff.removedMethods.forEach(m -> System.out.println("  - method: " + m));

		diff.modifiedMethods.forEach(m -> System.out.println("  * body changed: " + m));
	}
}
