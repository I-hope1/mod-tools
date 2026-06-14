package nipx;

import nipx.ref.UpdateRef;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.*;
import java.lang.instrument.ClassDefinition;
import java.util.*;

import static nipx.AnnotationTransformer.dot2slash;
import static nipx.HotSwapAgent.*;

public class Injector {
	static final String CL_ELEMENT = "arc/scene/Element";
	public static void redefineOneClass(Class<?> theClass, byte[] bytes) {
		try {
			try (OutputStream stream = new FileOutputStream("F:/classes/" + theClass.getName() + ".class")) {
				stream.write(bytes);
			}
			inst.redefineClasses(new ClassDefinition(theClass, bytes));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public static void redefine(Class<?> clazz, String methodName, String lambdaType) {
		redefine(clazz, methodName, "(L" + lambdaType + ";)V", lambdaType);
	}

	static Map<Class<?>, Runnable> todos = new HashMap<>();
	static Map<Class<?>, byte[]> cacheBytecodes = new HashMap<>();

	/** 批量处理所有任务  */
	public static void batchProcess() {
		for (var entry : todos.entrySet()) {
			entry.getValue().run();
		}
		todos.clear();
	}
	public static void redefine(Class<?> clazz, String methodName, String methodDesc, String lambdaType) {
		redefine(clazz, methodName, methodDesc, lambdaType, 1);
	}
	public static void redefine(Class<?> clazz, String methodName, String methodDesc, String lambdaType, int lambdaSlot) {
		var fromBytecode = cacheBytecodes.computeIfAbsent(clazz, _ -> fetchOriginalBytecode(clazz));
		var toBytecode = injectForElement(fromBytecode, methodName, methodDesc, lambdaType, lambdaSlot);
		todos.put(clazz, () -> redefineOneClass(clazz, toBytecode));
	}
	/**
	 * 拦截 Element.update(Runnable) 等方法，
	 * 在方法入口把参数 r 替换为 UpdateRef.wrap(this, r)。
	 * <p>
	 * 原始字节码：
	 * this.update = r;
	 * <p>
	 * 注入后效果：
	 * r = UpdateRef.wrap(this, r);   ← 插入
	 * this.update = r;               ← 原样保留
	 *
	 * @param lambdaSlot lambda 参数的slot（long，double占两位slot）
	 */
	private static byte[] injectForElement(
	 byte[] bytes, String methodName, String methodDesc,
	 String lambdaType, int lambdaSlot) {
		// 拦截 setText(Lprov;) 入口
		// var0=this(Label), var1=prov
		// 插入: var1 = UpdateRef.wrap(this, var1)
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr,  ClassWriter.COMPUTE_MAXS);
		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				super.visit(version, access, name, signature, superName, interfaces);
			}
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				// 只拦截对应的方法
				if (!methodName.equals(name) || !methodDesc.equals(descriptor)) { return mv; }
				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					@Override
					protected void onMethodEnter() {
						visitVarInsn(ALOAD, 0); // load this
						// visitTypeInsn(CHECKCAST, dot2slash(CL_ELEMENT));
						visitVarInsn(ALOAD, lambdaSlot); // load lambda
						visitMethodInsn(
						 INVOKESTATIC,
						 dot2slash(UpdateRef.class),
						 "wrap",
						 "(L" + CL_ELEMENT + ";L" + lambdaType + ";)L" + lambdaType + ";",
						 false
						);
						visitVarInsn(ASTORE, lambdaSlot);
					}
				};
			}
		};
		cr.accept(cv, ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}
	/**
	 * 工具方法：从 Lambda 实例提取宿主类名
	 * 例如：com.example.MyUI$$Lambda$1/0x0000000800060840 -> com.example.MyUI
	 */
	public static String getHostClassName(Object lambdaOrInnerClass) {
		if (lambdaOrInnerClass == null) return null;
		String name = lambdaOrInnerClass.getClass().getName();

		int lambdaIndex = name.indexOf("$$Lambda$");
		if (lambdaIndex != -1) {
			return name.substring(0, lambdaIndex);
		}

		// 兼容匿名内部类的写法: com.example.MyUI$1
		int innerIndex = name.indexOf('$');
		if (innerIndex != -1) {
			return name.substring(0, innerIndex);
		}

		return name;
	}
}
