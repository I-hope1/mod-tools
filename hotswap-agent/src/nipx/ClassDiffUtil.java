package nipx;

import nipx.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.*;

/**
 * 类差异分析工具
 * 用于检测类文件的变更，支持字段、方法的增删改检测
 * 主要用于热重载系统的智能更新决策
 *
 * 核心功能：
 * 1. 比较两个版本的类字节码差异
 * 2. 检测类继承关系变更（父类、接口）
 * 3. 检测字段的增删变化
 * 4. 检测方法的增删改变化
 * 5. 提供详细的变更日志输出
 */
public final class ClassDiffUtil {
	/** 线程本地变量，存储比较上下文，避免重复创建对象 */
	private static final ThreadLocal<ComparisonContext> CONTEXT = ThreadLocal.withInitial(ComparisonContext::new);

	/**
	 * 比较上下文类
	 * 封装了一次比较操作所需的所有临时数据结构
	 * 使用对象复用模式提升性能
	 */
	private static class ComparisonContext {
		/** 旧版本接口映射表：hash -> 接口全限定名 */
		final LongObjectMap<String>
		 oldInterfaces = new LongObjectMap<>(),
		 /** 新版本接口映射表：hash -> 接口全限定名 */
		 newInterfaces = new LongObjectMap<>();

		/** 字段映射表：复合hash(字段名+描述符) -> 字段名 */
		final LongObjectMap<String>     fieldMap  = new LongObjectMap<>(256);
		/** 方法映射表：复合hash(方法名+描述符) -> MethodNode */
		final LongObjectMap<MethodNode> methodMap = new LongObjectMap<>(256);
		/** 差异结果对象 */
		final ClassDiff                 result    = new ClassDiff();

		/**
		 * 重置上下文状态，为下一次比较做准备
		 * 清空所有映射表和结果集
		 */
		void reset() {
			oldInterfaces.clear();
			newInterfaces.clear();

			fieldMap.clear();
			methodMap.clear();
			result.reset();
		}
	}


	/**
	 * 比较两个字节码数组的差异
	 * @param oldBytes 旧版本字节码数组
	 * @param newBytes 新版本字节码数组
	 * @return 差异结果对象，请勿缓存使用（每次调用都返回新的实例）
	 */
	public static ClassDiff diff(byte[] oldBytes, byte[] newBytes) {
		ClassNode oldNode = parse(oldBytes);
		ClassNode newNode = parse(newBytes);
		return diff(oldNode, newNode);
	}

	/**
	 * 解析字节码为ASM树节点
	 * <pre>
	 * 性能优化说明：
	 * - SKIP_DEBUG：跳过调试信息（行号、局部变量表等），提升解析速度
	 * - SKIP_FRAMES：跳过栈帧信息，减少内存占用
	 * 这些优化对逻辑对比无影响，但显著提升性能
	 * @param bytes 字节码数组
	 * @return ASM ClassNode 对象
	 */
	private static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
	}

	/**
	 * 类差异结果封装类
	 * 包含所有类型的变更信息
	 */
	public static final class ClassDiff {
		/** 方法体被修改的方法列表：存储格式为 "methodName + methodDescriptor" */
		final List<String> modifiedBodyMethods = new ArrayList<>();
		/** 新增的方法列表：存储格式为 "methodName + methodDescriptor" */
		final List<String> addedMethods        = new ArrayList<>();
		/** 删除的方法列表：存储格式为 "methodName + methodDescriptor" */
		final List<String> removedMethods      = new ArrayList<>();
		/** 字段变更列表：包括新增和删除的字段，存储格式为 "+ fieldName" 或 "- fieldName" */
		final List<String> changedFields       = new ArrayList<>();

		/** 继承层次是否发生变化（父类或接口变更） */
		boolean hierarchyChanged = false;
		/** 错误信息列表：记录严重的不兼容变更 */
		final List<String> errors = new ArrayList<>();

		/**
		 * 重置所有集合，清空之前的比较结果
		 */
		void reset() {
			modifiedBodyMethods.clear();
			addedMethods.clear();
			removedMethods.clear();
			changedFields.clear();
			hierarchyChanged = false;
			errors.clear();
		}

		/**
		 * 检查是否存在任何变更
		 * @return true表示有变更，false表示无变更
		 */
		public boolean hasChange() {
			return hierarchyChanged || !(modifiedBodyMethods.isEmpty() && addedMethods.isEmpty() &&
			                             removedMethods.isEmpty() && changedFields.isEmpty() &&
			                             errors.isEmpty());
		}

		/**
		 * 检查是否存在结构性变更（影响类结构的变更）
		 * 结构性变更包括：方法增删、字段增删
		 * @return true表示有结构性变更
		 */
		public boolean structureChanged() {
			return !(addedMethods.isEmpty() && removedMethods.isEmpty() && changedFields.isEmpty());
		}
	}

	/** 哈希键提取函数：将字符串转换为long哈希值 */
	static ToLongFunction<String>   keyExtractor = Utils::hash;
	/** 值映射函数：恒等映射 */
	static Function<String, String> valueMapper  = i -> i;

	/**
	 * 比较两个类节点的差异
	 * 核心算法采用"映射销毁法"：
	 * 1. 先将旧版本元素全部放入映射表
	 * 2. 遍历新版本元素，在映射表中查找匹配项
	 * 3. 找到则移除，未找到则是新增
	 * 4. 映射表中剩余的就是被删除的元素
	 *
	 * @param oldC 旧版本类节点
	 * @param newC 新版本类节点
	 * @return 差异结果
	 */
	private static ClassDiff diff(ClassNode oldC, ClassNode newC) {
		ComparisonContext ctx = CONTEXT.get();
		ctx.reset();

		ClassDiff d = ctx.result;

		// 检查父类是否变更
		if (!Objects.equals(oldC.superName, newC.superName)) {
			d.hierarchyChanged = true;
			d.errors.add("! CRITICAL: Superclass changed: " + oldC.superName + " -> " + newC.superName);
		}

		// 检查接口变更
		var oldInterfacesMap = ctx.oldInterfaces;
		oldInterfacesMap.putAll(oldC.interfaces, keyExtractor, valueMapper);
		var newInterfacesMap = ctx.newInterfaces;
		newInterfacesMap.putAll(newC.interfaces, keyExtractor, valueMapper);

		// 比较接口集合是否相等
		if (!Objects.equals(oldInterfacesMap, newInterfacesMap)) {
			d.hierarchyChanged = true;
			d.errors.add("! CRITICAL: Interfaces changed");

			// 详细记录具体变更
			for (String itf : newC.interfaces) {
				// 如果在旧接口中找不到，则是新增接口
				if (oldInterfacesMap.remove(Utils.hash(itf)) == null) {
					d.errors.add("+ " + itf);
					d.hierarchyChanged = true;
				}
			}
			// 剩余的旧接口就是被删除的
			oldInterfacesMap.forEachValue(itf -> {
				d.errors.add("- " + itf);
				d.hierarchyChanged = true;
			});
		}

		// 字段对比：检测字段的增删变化
		var oldFieldsMap = ctx.fieldMap;
		// 将旧版本所有字段放入映射表，key为字段名和描述符的复合哈希
		for (FieldNode f : oldC.fields) {
			oldFieldsMap.put(Utils.computeCompositeHash(f.name, f.desc), f.name);
		}

		// 遍历新版本字段
		for (FieldNode newF : newC.fields) {
			long key = Utils.computeCompositeHash(newF.name, newF.desc);
			// 如果旧映射表中没有此key，说明是新增字段
			if (oldFieldsMap.remove(key) == null) {
				d.changedFields.add("+ " + newF.name);
			}
		}
		// 映射表中剩余的就是被删除的字段
		oldFieldsMap.forEachValue(removedFieldName -> {
			d.changedFields.add("- " + removedFieldName);
		});

		// 方法对比逻辑：同样采用映射销毁法
		var oldMethods = ctx.methodMap;
		// 将旧版本所有方法放入映射表，key为方法名和描述符的复合哈希
		for (MethodNode m : oldC.methods) {
			oldMethods.put(Utils.computeCompositeHash(m.name, m.desc), m);
		}

		// 遍历新版本方法
		for (MethodNode newM : newC.methods) {
			long       key  = Utils.computeCompositeHash(newM.name, newM.desc);
			MethodNode oldM = oldMethods.remove(key);

			if (oldM == null) {
				// 新增方法：记录方法名+描述符
				d.addedMethods.add(newM.name + newM.desc);
			} else if (isCodeChanged(oldM, newM)) {
				// 方法存在但代码发生了变化
				d.modifiedBodyMethods.add(newM.name + newM.desc);
			}
		}
		// 映射表中剩余的就是被删除的方法
		oldMethods.forEachValue(m -> {
			d.removedMethods.add(m.name + m.desc);
		});

		return d;
	}

	/**
	 * 检测方法代码是否发生变化
	 * @param m1 旧版本方法节点
	 * @param m2 新版本方法节点
	 * @return true表示代码有变化，false表示代码相同
	 *
	 * 检测策略：
	 * 1. 首先快速检查访问权限是否改变（不同则肯定有变化）
	 * 2. 然后计算两个方法的逻辑指纹进行精确对比
	 */
	private static boolean isCodeChanged(MethodNode m1, MethodNode m2) {
		// 快速判断：访问权限不同则肯定有变化
		// 这是一个重要的早期退出条件，避免不必要的复杂计算
		if (m1.access != m2.access) return true;

		// 计算逻辑指纹进行精确对比
		// 通过指令流特征生成哈希值来判断代码是否实质改变
		return calculateMethodHash(m1) != calculateMethodHash(m2);
	}

	/**
	 * 计算方法的逻辑指纹哈希值
	 * <pre>
	 * 实现原理：
	 * 使用 MethodFingerprinter 访问者模式遍历方法的所有指令，
	 * 提取关键特征（如操作码、常量引用等）生成唯一的哈希值。
	 * 相同逻辑的方法会产生相同的哈希值，不同的实现会产生不同哈希值。
	 * @param mn 方法节点
	 * @return 该方法的哈希指纹
	 */
	public static long calculateMethodHash(MethodNode mn) {
		MethodFingerprinter fingerprinter = MethodFingerprinter.CONTEXT.get();
		fingerprinter.reset();
		mn.accept(fingerprinter);
		return fingerprinter.getHash();
	}

	/**
	 * 输出类差异日志
	 * @param className 类的全限定名
	 * @param diff      差异结果对象
	 *
	 * 日志格式示例：
	 * [DIFF] com.example.MyClass:
	 *  !!!Errors:   [! CRITICAL: Superclass changed: java.lang.Object -> java.util.ArrayList]
	 *  *Modified: [method1()V, method2(I)Z]
	 *  +Added:    [newMethod()V]
	 *  -Removed:  [oldMethod()V]
	 *  !Fields:   [+ newField, - oldField]
	 */
	static void logDiff(String className, ClassDiffUtil.ClassDiff diff) {
		// 如果没有任何变更，直接返回不输出日志
		if (!diff.hasChange()) return;

		// 预估StringBuilder容量以提升性能
		// [DIFF] (7) + className (平均30字符) + 4行内容 (平均200字符) ≈ 256
		StringBuilder sb = new StringBuilder(256);
		sb.append("[DIFF] ").append(className).append(":\n");

		// 依次追加各种变更类型的信息
		appendIfNotEmpty(sb, " !!!Errors:   ", diff.errors);
		appendIfNotEmpty(sb, " *Modified: ", diff.modifiedBodyMethods);
		appendIfNotEmpty(sb, " +Added:    ", diff.addedMethods);
		appendIfNotEmpty(sb, " -Removed:  ", diff.removedMethods);
		appendIfNotEmpty(sb, " !Fields:   ", diff.changedFields);

		// 通过HotSwapAgent输出日志
		HotSwapAgent.info(sb.toString());
	}

	/** 系统换行符常量，避免重复调用System.lineSeparator() */
	private static final String NE = System.lineSeparator();

	/**
	 * 辅助方法：如果集合非空则追加到StringBuilder
	 * @param sb StringBuilder对象
	 * @param label 标签前缀
	 * @param items 要追加的集合
	 */
	private static void appendIfNotEmpty(StringBuilder sb, String label, Collection<?> items) {
		if (items != null && !items.isEmpty()) {
			sb.append(label).append(items).append(NE);
		}
	}
}
