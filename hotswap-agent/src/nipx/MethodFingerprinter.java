package nipx;

import nipx.util.CRC64;
import org.objectweb.asm.*;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * <pre>方法指纹生成器
 * 用于为Java方法生成唯一的哈希指纹，主要用于热重载时的方法匹配
 *
 * 工作原理：
 * 1. 继承ASM的MethodVisitor，遍历方法的所有字节码指令
 * 2. 将每条指令的特征信息通过CRC64算法累积计算哈希值
 * 3. 忽略调试信息（行号、局部变量等），只关注实际执行逻辑
 * 4. 相同逻辑的方法会产生相同的哈希值，用于精确匹配
 */
public final class MethodFingerprinter extends MethodVisitor {

	/* ================= 标记常量 ================= */

	/** LDC指令标记 */
	private static final int MARK_LDC             = 0x7F000010;
	/** IINC指令标记 */
	private static final int MARK_IINC            = 0x7F000011;
	/** TABLESWITCH指令标记 */
	private static final int MARK_TABLESWITCH     = 0x7F000012;
	/** LOOKUPSWITCH指令标记 */
	private static final int MARK_LOOKUPSWITCH    = 0x7F000013;
	/** TRY_CATCH块标记 */
	private static final int MARK_TRY_CATCH       = 0x7F000014;
	/** INVOKEDYNAMIC指令标记 */
	private static final int MARK_INVOKEDYNAMIC   = 0x7F000015;
	/** MULTIANEWARRAY指令标记 */
	private static final int MARK_MULTIANEWARRAY  = 0x7F000016;
	/** LABEL标记 */
	private static final int MARK_LABEL           = 0x7F000017;
	/** JUMP指令标记 */
	private static final int MARK_JUMP            = 0x7F000018;
	/** 参数注解计数标记 */
	private static final int MARK_PARAM_ANNOT_CNT = 0x7F000019;
	/** 注解默认值标记 */
	private static final int MARK_ANNOT_DEFAULT   = 0x7F00001A;

	/* ================= 标签ID管理 ================= */

	/** 标签到ID的映射，用于统一标识跳转目标 */
	private final Map<Label, Integer> labelIds    = new IdentityHashMap<>();
	/** 下一个可用的标签ID */
	private       int                 nextLabelId = 0;

	/**
	 * 获取标签的唯一ID，如果不存在则分配新的ID
	 * @param l 标签对象
	 * @return 标签的整数ID
	 */
	private int getLabelId(Label l) {
		return labelIds.computeIfAbsent(l, _ -> nextLabelId++);
	}

	/* ================= CRC哈希计算 ================= */

	/** 当前累积的CRC64哈希值 */
	private long crc = CRC64.init();

	/**
	 * 构造函数，初始化ASM访问器
	 */
	public MethodFingerprinter() {
		super(Opcodes.ASM9);
	}

	/**
	 * 重置指纹生成器状态，为下一个方法做准备
	 */
	public void reset() {
		crc = CRC64.init();
		labelIds.clear();
		nextLabelId = 0;
	}

	/**
	 * 获取最终计算出的哈希值
	 * @return 64位CRC哈希值
	 */
	public long getHash() {
		return CRC64.finish(crc);
	}

	/* ================= 辅助方法 ================= */

	/**
	 * 更新CRC值与整数
	 * @param v 要更新的整数值
	 */
	private void updateInt(int v) {
		crc = CRC64.updateInt(crc, v);
	}

	/**
	 * 更新CRC值与长整数
	 * @param v 要更新的长整数值
	 */
	private void updateLong(long v) {
		crc = CRC64.updateLong(crc, v);
	}

	/**
	 * 更新CRC值与字符串
	 * @param s 要更新的字符串，null会被视为0
	 */
	private void updateString(String s) {
		if (s == null) {
			updateInt(0);
		} else {
			crc = CRC64.updateStringUTF16(crc, s);
		}
	}

	/**
	 * 更新CRC值与方法句柄
	 * @param h 要更新的句柄对象
	 */
	private void updateHandle(Handle h) {
		updateInt(h.getTag());           // 句柄类型
		updateString(h.getOwner());      // 所属类
		updateString(h.getName());       // 方法名
		updateString(h.getDesc());       // 方法描述符
		updateInt(h.isInterface() ? 1 : 0); // 是否接口方法
	}

	/* ================= 字节码指令处理 ================= */

	/**
	 * 处理无操作数指令（如NOP, ACONST_NULL等）
	 */
	@Override
	public void visitInsn(int opcode) {
		updateInt(opcode);
	}

	/**
	 * 处理单操作数整数指令（如BIPUSH, SIPUSH, NEWARRAY）
	 */
	@Override
	public void visitIntInsn(int opcode, int operand) {
		updateInt(opcode);
		updateInt(operand);
	}

	/**
	 * 处理局部变量指令（如ILOAD, ISTORE等）
	 */
	@Override
	public void visitVarInsn(int opcode, int var) {
		updateInt(opcode);
		updateInt(var);
	}

	/**
	 * 处理类型指令（如NEW, ANEWARRAY, CHECKCAST, INSTANCEOF）
	 */
	@Override
	public void visitTypeInsn(int opcode, String type) {
		updateInt(opcode);
		updateString(type);
	}

	/**
	 * 处理字段访问指令（GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC）
	 */
	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		updateInt(opcode);
		updateString(owner);  // 字段所属类
		updateString(name);   // 字段名
		updateString(desc);   // 字段描述符
	}

	/**
	 * 处理方法调用指令（INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE）
	 */
	@Override
	public void visitMethodInsn(int opcode, String owner, String name,
	                            String desc, boolean isInterface) {
		updateInt(opcode);
		updateString(owner);      // 方法所属类
		updateString(name);       // 方法名
		updateString(desc);       // 方法描述符
		updateInt(isInterface ? 1 : 0); // 是否接口方法
	}

	/**
	 * 处理动态调用指令（invokedynamic）
	 * 这是Lambda表达式和方法引用的核心实现机制
	 */
	@Override
	public void visitInvokeDynamicInsn(String name,
	                                   String desc,
	                                   Handle bsm,
	                                   Object... bsmArgs) {

		updateInt(MARK_INVOKEDYNAMIC);

		updateString(name);    // 动态调用的方法名
		updateString(desc);    // 方法描述符
		updateHandle(bsm);     // 引导方法句柄

		// 处理引导方法参数
		if (bsmArgs == null) {
			updateInt(0);
		} else {
			updateInt(bsmArgs.length);
			for (Object o : bsmArgs) {
				updateConstant(o);
			}
		}
	}

	/**
	 * 处理常量加载指令（LDC）
	 */
	@Override
	public void visitLdcInsn(Object value) {
		updateInt(MARK_LDC);
		updateConstant(value);
	}

	/**
	 * 处理局部变量自增指令（IINC）
	 */
	@Override
	public void visitIincInsn(int var, int increment) {
		updateInt(MARK_IINC);
		updateInt(var);
		updateInt(increment);
	}

	/**
	 * 处理表查找switch指令
	 */
	@Override
	public void visitTableSwitchInsn(int min, int max,
	                                 Label dflt, Label... labels) {
		updateInt(MARK_TABLESWITCH);
		updateInt(min);          // 最小值
		updateInt(max);          // 最大值
		updateInt(getLabelId(dflt)); // 默认分支标签

		// 处理所有case标签
		for (Label l : labels) {
			updateInt(getLabelId(l));
		}
	}

	/**
	 * 处理查找表switch指令
	 */
	@Override
	public void visitLookupSwitchInsn(Label dflt,
	                                  int[] keys,
	                                  Label... labels) {

		updateInt(MARK_LOOKUPSWITCH);
		updateInt(getLabelId(dflt)); // 默认分支标签

		// 处理键值对
		for (int i = 0; i < keys.length; i++) {
			updateInt(keys[i]);              // 键
			updateInt(getLabelId(labels[i])); // 对应的标签
		}
	}

	/**
	 * 处理跳转指令（IFEQ, IFNULL等）
	 */
	@Override
	public void visitJumpInsn(int opcode, Label label) {
		updateInt(opcode);
		updateInt(MARK_JUMP);
		updateInt(getLabelId(label)); // 跳转目标标签
	}

	/**
	 * 处理标签（代码位置标记）
	 */
	@Override
	public void visitLabel(Label label) {
		updateInt(MARK_LABEL);
		updateInt(getLabelId(label));
	}

	/**
	 * 处理异常处理块
	 */
	@Override
	public void visitTryCatchBlock(Label start,
	                               Label end,
	                               Label handler,
	                               String type) {

		updateInt(MARK_TRY_CATCH);
		updateInt(getLabelId(start));   // try块开始
		updateInt(getLabelId(end));     // try块结束
		updateInt(getLabelId(handler)); // catch处理程序
		updateString(type);             // 异常类型
	}

	/**
	 * 处理多维数组创建指令
	 */
	@Override
	public void visitMultiANewArrayInsn(String desc, int dims) {
		updateInt(MARK_MULTIANEWARRAY);
		updateString(desc); // 数组类型描述符
		updateInt(dims);    // 维度数量
	}

	/* ================= 注解处理 ================= */

	/**
	 * 处理注解默认值
	 */
	@Override
	public AnnotationVisitor visitAnnotationDefault() {
		updateInt(MARK_ANNOT_DEFAULT);
		return new FingerprintAnnotationVisitor();
	}

	/**
	 * 处理可注解参数计数
	 */
	@Override
	public void visitAnnotableParameterCount(int parameterCount,
	                                         boolean visible) {
		updateInt(MARK_PARAM_ANNOT_CNT);
		updateInt(parameterCount);
		updateInt(visible ? 1 : 0);
	}

	/* ================= 常量处理 ================= */

	/**
	 * 处理各种类型的常量值
	 * @param cst 常量对象
	 */
	private void updateConstant(Object cst) {
		switch (cst) {
			case null -> updateInt(0);
			case Integer i -> {
				updateInt(1);      // 类型标记
				updateInt(i);      // 整数值
			}
			case Long l -> {
				updateInt(2);      // 类型标记
				updateLong(l);     // 长整数值
			}
			case Float f -> {
				updateInt(3);      // 类型标记
				updateInt(Float.floatToRawIntBits(f)); // 浮点数位表示
			}
			case Double d -> {
				updateInt(4);      // 类型标记
				updateLong(Double.doubleToRawLongBits(d)); // 双精度位表示
			}
			case String s -> {
				updateInt(5);      // 类型标记
				updateString(s);   // 字符串值
			}
			case Type t -> {
				updateInt(6);      // 类型标记
				updateString(t.getDescriptor()); // 类型描述符
			}
			case Handle h -> {
				updateInt(7);      // 类型标记
				updateHandle(h);   // 句柄对象
			}
			case ConstantDynamic cd -> {
				updateInt(8);                          // 类型标记
				updateString(cd.getName());            // 名称
				updateString(cd.getDescriptor());      // 描述符
				updateHandle(cd.getBootstrapMethod()); // 引导方法

				// 处理引导方法参数
				int count = cd.getBootstrapMethodArgumentCount();
				updateInt(count);
				for (int i = 0; i < count; i++) {
					updateConstant(cd.getBootstrapMethodArgument(i));
				}
			}
			default -> throw new IllegalStateException(
			 "Unhandled constant type: " + cst.getClass()
			);
		}
	}

	/* ================= 注解访问器 ================= */

	/**
	 * 指纹注解访问器
	 * 用于处理注解信息并将其纳入指纹计算
	 */
	private final class FingerprintAnnotationVisitor extends AnnotationVisitor {

		FingerprintAnnotationVisitor() {
			super(Opcodes.ASM9);
		}

		/**
		 * 处理普通注解值
		 */
		@Override
		public void visit(String name, Object value) {
			updateString(name);
			updateConstant(value);
		}

		/**
		 * 处理枚举注解值
		 */
		@Override
		public void visitEnum(String name, String desc, String value) {
			updateString(name);
			updateString(desc);  // 枚举类型描述符
			updateString(value); // 枚举值名称
		}

		/**
		 * 处理嵌套注解
		 */
		@Override
		public AnnotationVisitor visitAnnotation(String name, String desc) {
			updateString(name);
			updateString(desc);
			return this; // 返回自身继续处理嵌套内容
		}

		/**
		 * 处理注解数组值
		 */
		@Override
		public AnnotationVisitor visitArray(String name) {
			updateString(name);
			return this; // 返回自身继续处理数组元素
		}
	}

	/* ================= 忽略调试信息 ================= */

	/**
	 * 忽略行号信息（不影响程序逻辑）
	 */
	@Override
	public void visitLineNumber(int line, Label start) { }

	/**
	 * 忽略局部变量信息（不影响程序逻辑）
	 */
	@Override
	public void visitLocalVariable(String name, String desc,
	                               String sig, Label start,
	                               Label end, int index) { }
}
