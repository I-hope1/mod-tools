package nipx;

import arc.scene.Element;
import nipx.ref.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.*;

import static nipx.HotSwapAgent.*;
import static nipx.MyClassFileTransformer.dot2slash;

public class LambdaRef {
	static final String      CL_ELEMENT      = "arc/scene/Element";
	static final String      CL_LABEL        = "arc/scene/ui/Label";

	public static void init() {
		{
			var bytes = fetchOriginalBytecode(Element.class);
			bytes = injectUpdateWrapper(bytes, dot2slash(Element.class), Element.class.getClassLoader());
			try {
				inst.redefineClasses(new ClassDefinition(Element.class, bytes));
			} catch (ClassNotFoundException | UnmodifiableClassException e) {
				throw new RuntimeException(e);
			}
		}
		{
			var bytes = fetchOriginalBytecode(Label.class);
			bytes = injectLabelSetTextWrapper(bytes, dot2slash(Label.class), Label.class.getClassLoader());
			try {
				inst.redefineClasses(new ClassDefinition(Label.class, bytes));
			} catch (ClassNotFoundException | UnmodifiableClassException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * 拦截 Element.update(Runnable) 方法，
	 * 在方法入口把参数 r 替换为 UpdateRef.wrap(this, r)。
	 * <p>
	 * 原始字节码：
	 * this.update = r;
	 * <p>
	 * 注入后效果：
	 * r = UpdateRef.wrap(this, r);   ← 插入
	 * this.update = r;               ← 原样保留
	 */
	private static byte[] injectUpdateWrapper(byte[] bytes, String slashClassName, ClassLoader classLoader) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				// 只拦截 update(Ljava/lang/Runnable;)Larc/scene/Element;
				if (!"update".equals(name) || !"(Ljava/lang/Runnable;)Larc/scene/Element;".equals(descriptor)) { return mv; }

				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					@Override
					protected void onMethodEnter() {
						// 在方法最开头执行：r = UpdateRef.wrap(this, r)
						// var0 = this, var1 = r (Runnable)
						visitVarInsn(ALOAD, 0);  // this (Element)
						visitVarInsn(ALOAD, 1);  // r (Runnable)
						visitMethodInsn(
						 INVOKESTATIC,
						 dot2slash(UpdateRef.class),          // nipx/UpdateRef
						 "wrap",
						 "(L" + CL_ELEMENT + ";Ljava/lang/Runnable;)Ljava/lang/Runnable;",
						 false
						);
						visitVarInsn(ASTORE, 1); // 覆盖局部变量 r
						// 后续原始代码 this.update = r 自然存入包装后的 Runnable
					}
				};
			}
		};

		cr.accept(cv, ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}
	private static byte[] injectLabelSetTextWrapper(byte[] bytes, String slashClassName, ClassLoader classLoader) {
		// 拦截 setText(Lprov;) 入口
		// var0=this(Label), var1=prov
		// 插入: var1 = UpdateRef.wrapProv(this, var1)
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				// 只拦截 setText(Larc/struct/prov;)V
				if (!"setText".equals(name) || !"(Larc/func/Prov;)V".equals(descriptor)) { return mv; }
				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					@Override
					protected void onMethodEnter() {
						visitVarInsn(ALOAD, 0);
						visitVarInsn(ALOAD, 1);
						visitMethodInsn(
						 INVOKESTATIC,
						 dot2slash(ProvRef.class),
						 "wrap",
						 "(L" + CL_ELEMENT + ";Larc/func/Prov;)Larc/func/Prov;",
						 false
						);
						visitVarInsn(ASTORE, 1);
					}
				};
			}
		};
		cr.accept(cv, ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}
	/**
	 * 当某个类被 HotSwap 重载时调用。
	 * 清除所有 UpdateRef 中来自该类的 lambda，避免 NoSuchMethodError。
	 * @param dotClassName 被重载的类名，如 com.example.MyView
	 */
	public static void onClassRedefined(String dotClassName) {
		// InstanceTracker 里用 WeakReference 存着所有 @Tracker 实例
		// 直接过滤出 UpdateRef 类型
		int cleared = 0;
		for (Object instance : InstanceTracker.getInstances(UpdateRef.class)) {
			if (instance instanceof UpdateRef ref) {
				if (ref.clearIfFromClass(dotClassName)) {
					cleared++;
				}
			}
		}
		for (Object instance : InstanceTracker.getInstances(ProvRef.class)) {
			if (instance instanceof ProvRef ref) {
				if (ref.clearIfFromClass(dotClassName)) {
					cleared++;
				}
			}
		}
		if (cleared > 0) {
			info("HotSwap: cleared " + cleared + " UpdateRef lambda(s) from " + dotClassName);
		}
	}
}
