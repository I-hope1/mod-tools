package nipx;

import nipx.util.Utils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * 类差异分析工具
 * 用于检测类文件的变更，支持字段、方法的增删改检测
 * 主要用于热重载系统的智能更新决策
 */
public final class ClassDiffUtil {

	private ClassDiffUtil() { }

	/**
	 * 比较两个字节码数组的差异
	 * @param oldBytes 旧版本字节码
	 * @param newBytes 新版本字节码
	 * @return 差异结果
	 */
	public static ClassDiff diff(byte[] oldBytes, byte[] newBytes) {
		ClassNode oldNode = parse(oldBytes);
		ClassNode newNode = parse(newBytes);
		return diff(oldNode, newNode);
	}

	/**
	 * 解析字节码为ASM树节点
	 * 跳过调试信息以提高性能并确保逻辑对比的准确性
	 */
	private static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
	}

	/**
	 * 类差异结果封装
	 * 包含修改的方法、新增的方法、删除的方法以及字段变更信息
	 */
	public static final class ClassDiff {
		final List<String> modifiedBodyMethods = new ArrayList<>();
		final List<String> addedMethods        = new ArrayList<>();
		final List<String> removedMethods      = new ArrayList<>();
		// 字段变动通常会导致重定义失败，记录它们以进行预警
		final List<String> changedFields       = new ArrayList<>();

		boolean hierarchyChanged = false;
		final List<String> errors = new ArrayList<>();

		/** 检查是否存在任何变更 */
		public boolean hasChange() {
			return !(modifiedBodyMethods.isEmpty() && addedMethods.isEmpty() &&
			         removedMethods.isEmpty() && changedFields.isEmpty() &&
			         errors.isEmpty());
		}
		public boolean structureChanged() {
			return !(addedMethods.isEmpty() && removedMethods.isEmpty() && changedFields.isEmpty());
		}
	}

	/**
	 * 比较两个类节点的差异
	 */
	private static ClassDiff diff(ClassNode oldC, ClassNode newC) {
		ClassDiff d = new ClassDiff();

		if (!Objects.equals(oldC.superName, newC.superName)) {
			d.hierarchyChanged = true;
			d.errors.add("! CRITICAL: Superclass changed: " + oldC.superName + " -> " + newC.superName);
		}
		if (!Objects.equals(new HashSet<>(oldC.interfaces), new HashSet<>(newC.interfaces))) {
			d.hierarchyChanged = true;
			d.changedFields.add("! CRITICAL: Interfaces changed");
		}

		// 字段对比：检测字段的增删
		Map<Long, String> oldFieldsMap = new HashMap<>(oldC.fields.size());
		for (FieldNode f : oldC.fields) {
			oldFieldsMap.put(Utils.computeCompositeHash(f.name, f.desc), f.name);
		}

		for (FieldNode newF : newC.fields) {
			long key = Utils.computeCompositeHash(newF.name, newF.desc);
			// 如果旧 Map 里没有，说明是新增
			if (oldFieldsMap.remove(key) == null) {
				d.changedFields.add("+ " + newF.name);
			}
		}
		// Map 中剩余的部分就是被删除的，直接取值，无需 split
		for (String removedFieldName : oldFieldsMap.values()) {
			d.changedFields.add("- " + removedFieldName);
		}

		// --- 方法对比逻辑：同样采用映射销毁法 ---
		Map<Long, MethodNode> oldMethods = new HashMap<>(oldC.methods.size());
		for (MethodNode m : oldC.methods) {
			oldMethods.put(Utils.computeCompositeHash(m.name, m.desc), m);
		}

		for (MethodNode newM : newC.methods) {
			long       key  = Utils.computeCompositeHash(newM.name, newM.desc);
			MethodNode oldM = oldMethods.remove(key);
			if (oldM == null) {
				// 新增方法：记录 逻辑名 + 描述符
				d.addedMethods.add(newM.name + newM.desc);
			} else if (isCodeChanged(oldM, newM)) {
				// 修改方法
				d.modifiedBodyMethods.add(newM.name + newM.desc);
			}
		}
		// 剩余的是删除的方法
		for (MethodNode m : oldMethods.values()) {
			d.removedMethods.add(m.name + m.desc);
		}

		return d;
	}

	/**
	 * 检测方法代码是否发生变化
	 * 通过指令流指纹进行快速对比
	 */
	private static boolean isCodeChanged(MethodNode m1, MethodNode m2) {
		// 快速判断：访问权限不同则肯定有变化
		if (m1.access != m2.access) return true;

		// 计算逻辑指纹进行精确对比
		return calculateMethodHash(m1) != calculateMethodHash(m2);
	}

	/**
	 * 计算方法的逻辑指纹哈希值
	 * 通过遍历指令流提取关键特征来生成唯一标识
	 */
	public static long calculateMethodHash(MethodNode mn) {
		MethodFingerprinter fingerprinter = MethodFingerprinter.CONTEXT.get();
		fingerprinter.reset();
		mn.accept(fingerprinter);
		return fingerprinter.getHash();
	}

	/**
	 * 输出类差异日志
	 * @param className 类名
	 * @param diff      差异结果
	 */
	static void logDiff(String className, ClassDiff diff) {
		if (!diff.hasChange()) return;

		// 预估容量：[DIFF] (7) + className (avg 30) + 4行内容 (avg 200) ≈ 256
		StringBuilder sb = new StringBuilder(256);
		sb.append("[DIFF] ").append(className).append(":\n");

		appendIfNotEmpty(sb, " !!!Errors:   ", diff.errors);
		appendIfNotEmpty(sb, " *Modified: ", diff.modifiedBodyMethods);
		appendIfNotEmpty(sb, " +Added:    ", diff.addedMethods);
		appendIfNotEmpty(sb, " -Removed:  ", diff.removedMethods);
		appendIfNotEmpty(sb, " !Fields:   ", diff.changedFields);

		HotSwapAgent.info(sb.toString());
	}

	private static final String NE = System.lineSeparator();
	private static void appendIfNotEmpty(StringBuilder sb, String label, Collection<?> items) {
		if (items != null && !items.isEmpty()) {
			sb.append(label).append(items).append(NE);
		}
	}
}
