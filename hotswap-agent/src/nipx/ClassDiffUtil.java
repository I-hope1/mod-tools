package nipx;

import nipx.util.CRC64;
import org.objectweb.asm.*;
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
		final List<String> modifiedMethods = new ArrayList<>();
		final List<String> addedMethods    = new ArrayList<>();
		final List<String> removedMethods  = new ArrayList<>();
		// 字段变动通常会导致重定义失败，记录它们以进行预警
		final List<String> changedFields   = new ArrayList<>();

		/** 检查是否存在任何变更 */
		public boolean hasChange() {
			return !modifiedMethods.isEmpty() || !addedMethods.isEmpty() ||
			       !removedMethods.isEmpty() || !changedFields.isEmpty();
		}
	}

	/**
	 * 比较两个类节点的差异
	 */
	private static ClassDiff diff(ClassNode oldC, ClassNode newC) {
		ClassDiff d = new ClassDiff();

		// 字段对比：检测字段的增删
		Set<String> oldFields = new HashSet<>();
		for (FieldNode f : oldC.fields) oldFields.add(f.name + ":" + f.desc);

		Set<String> newFields = new HashSet<>();
		for (FieldNode f : newC.fields) {
			String key = f.name + ":" + f.desc;
			newFields.add(key);
			if (!oldFields.contains(key)) d.changedFields.add("+ " + f.name);
		}

		for (String oldKey : oldFields) {
			if (!newFields.contains(oldKey)) {
				String name = oldKey.split(":")[0];
				d.changedFields.add("- " + name);
			}
		}

		// 方法对比：检测方法的增删改
		Map<String, MethodNode> oldMethods = new HashMap<>();
		for (MethodNode m : oldC.methods) oldMethods.put(m.name + m.desc, m);

		for (MethodNode newM : newC.methods) {
			String     key  = newM.name + newM.desc;
			MethodNode oldM = oldMethods.remove(key);
			if (oldM == null) {
				d.addedMethods.add(key);
			} else if (isCodeChanged(oldM, newM)) {
				d.modifiedMethods.add(key);
			}
		}
		d.removedMethods.addAll(oldMethods.keySet());

		return d;
	}

	/**
	 * 检测方法代码是否发生变化
	 * 通过指令流指纹进行快速对比
	 */
	private static boolean isCodeChanged(MethodNode m1, MethodNode m2) {
		// 快速判断：指令数量或访问权限不同则肯定有变化
		if (m1.instructions.size() != m2.instructions.size()) return true;
		if (m1.access != m2.access) return true;

		// 计算逻辑指纹进行精确对比
		return calculateMethodHash(m1) != calculateMethodHash(m2);
	}

	/**
	 * 计算方法的逻辑指纹哈希值
	 * 通过遍历指令流提取关键特征来生成唯一标识
	 */
	public static long calculateMethodHash(MethodNode mn) {
		long     h     = 0;
		InsnList insns = mn.instructions;
		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode node = insns.get(i);

			// 混合操作码作为基础特征
			h = 31 * h + node.getOpcode();

			// 根据指令类型提取关键特征
			// 忽略Label的对象标识符（每次编译都会变化）
			// 重点捕获常量、字段名和方法名等稳定特征
			switch (node) {
				case MethodInsnNode min -> {
					h = h * 31 + hashString(min.owner);
					h = h * 31 + hashString(min.name);
					h = h * 31 + hashString(min.desc);
				}
				case FieldInsnNode fin -> {
					h = h * 31 + hashString(fin.owner);
					h = h * 31 + hashString(fin.name);
				}
				case LdcInsnNode ldcInsnNode -> {
					Object cst = ldcInsnNode.cst;
					long   cstHash;
					if (cst instanceof String s) {
						cstHash = hashString(s);
					} else if (cst == null) {
						cstHash = 0;
					} else {
						cstHash = cst.hashCode();
					}
					h = h * 31 + cstHash;
				}
				case VarInsnNode varInsnNode -> h = h * 31 + varInsnNode.var;
				case TypeInsnNode typeInsnNode -> h = h * 31 + hashString(typeInsnNode.desc);
				default -> { }
			}
		}
		return h;
	}

	/**
	 * 字符串哈希计算
	 * 使用CRC64算法确保高性能且无垃圾回收压力
	 */
	private static long hashString(String s) {
		return s == null ? 0 : CRC64.hashString(s);
	}

	/**
	 * 输出类差异日志
	 * @param className 类名
	 * @param diff      差异结果
	 */
	static void logDiff(String className, ClassDiff diff) {
		if (!diff.hasChange()) return;
		StringBuilder sb = new StringBuilder("[DIFF] ").append(className).append(":").append('\n');
		if (!diff.modifiedMethods.isEmpty()) sb.append(" *Modified: ").append(diff.modifiedMethods).append('\n');
		if (!diff.addedMethods.isEmpty()) sb.append(" +Added: ").append(diff.addedMethods).append('\n');
		if (!diff.removedMethods.isEmpty()) sb.append(" -Removed: ").append(diff.removedMethods).append('\n');
		if (!diff.changedFields.isEmpty()) sb.append(" !Fields: ").append(diff.changedFields).append('\n');
		HotSwapAgent.info(sb.toString());
	}
}
