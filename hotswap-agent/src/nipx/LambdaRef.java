package nipx;

import arc.func.*;
import arc.scene.Element;
import arc.scene.ui.*;
import arc.scene.ui.Label;
import arc.scene.ui.TextField.TextFieldValidator;
import arc.scene.ui.layout.Cell;
import nipx.ref.UpdateRef;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.*;

import static nipx.HotSwapAgent.*;
import static nipx.MyClassFileTransformer.dot2slash;

public class LambdaRef {
	static final String CL_ELEMENT = "arc/scene/Element";

	public static void init() {
		// 子类在前面，父类在后
		redefine(Button.class, "setDisabled", dot2slash(Boolp.class));
		redefine(Label.class, "setText", dot2slash(Prov.class));
		redefineCell();
		redefine(TextField.class, "setValidator", dot2slash(TextFieldValidator.class));
		redefine(Element.class, "update", dot2slash(Runnable.class));
		// redefineTable();
	}
	//region Cell
	private static void redefineCell() {
		var bytes = fetchOriginalBytecode(Cell.class);
		bytes = injectCell(bytes);
		try {
			inst.redefineClasses(new ClassDefinition(Cell.class, bytes));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	/**
	 * @see UpdateRef#wrap(Element, Cons)
	 * @see UpdateRef#wrap(Element, Cons)
	 * @see Cell#update(Cons)
	 * @see Cell#disabled(Boolf)
	 */
	private static byte[] injectCell(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				// 只拦截 update(Larc/func/Cons;)Larc/scene/ui/layout/Cell;
				if ("update".equals(name) &&  "(Larc/func/Cons;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Cons;");
				}
				if ("disabled".equals(name) && "(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Boolf;");
				}
				return mv;
			}
		};
		cr.accept(cv, ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}
	//endregion
	//region Table
	/* private static void redefineTable() {
		var bytes = fetchOriginalBytecode(Table.class);
		bytes = injectTable(bytes);
		try {
			inst.redefineClasses(new ClassDefinition(Table.class, bytes));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	private static byte[] injectTable(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
	   // todo
		};

	} */
	//endregion

	private static void redefine(Class<?> clazz, String methodName, String lambdaType) {
		redefine(clazz, methodName, "(L" + lambdaType + ";)V", lambdaType);
	}
	private static void redefine(Class<?> clazz, String methodName, String methodDesc, String lambdaType) {
		var bytes = fetchOriginalBytecode(clazz);
		bytes = inject(bytes, methodName, methodDesc, lambdaType);
		try {
			inst.redefineClasses(new ClassDefinition(clazz, bytes));
		} catch (ClassNotFoundException | UnmodifiableClassException e) {
			throw new RuntimeException(e);
		}
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
	 */
	private static byte[] inject(
	 byte[] bytes, String methodName, String methodDesc,
	 String lambdaType) {
		// 拦截 setText(Lprov;) 入口
		// var0=this(Label), var1=prov
		// 插入: var1 = UpdateRef.wrap(this, var1)
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				// 只拦截 setText(Larc/struct/prov;)V
				if (!methodName.equals(name) || !methodDesc.equals(descriptor)) { return mv; }
				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					@Override
					protected void onMethodEnter() {
						visitVarInsn(ALOAD, 0);
						visitVarInsn(ALOAD, 1);
						visitMethodInsn(
						 INVOKESTATIC,
						 dot2slash(UpdateRef.class),
						 "wrap",
						 "(L" + CL_ELEMENT + ";L" + lambdaType + ";)L" + lambdaType + ";",
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
		int cleared = 0;
		for (var ref : UpdateRef.getAll()) {
			UpdateRef updateRef = ref.get();
			if (updateRef != null && updateRef.clearIfFromClass(dotClassName)) {
				cleared++;
			}
		}
		UpdateRef.clearLambda();

		if (cleared > 0) {
			info("HotSwap: cleared " + cleared + " UpdateRef lambda(s) from " + dotClassName);
		}
	}
	private static class CellAdviceAdapter extends AdviceAdapter {
		private final String lambdaType;
		public CellAdviceAdapter(MethodVisitor mv, int access, String name, String descriptor,
		                         String lambdaType) {
			super(Opcodes.ASM9, mv, access, name, descriptor);
			this.lambdaType = lambdaType;
		}
		@Override
		protected void onMethodEnter() {
			// UpdateRef.wrap(this.element, cons)
			// Cell.element 是 package-private 字段
			visitVarInsn(ALOAD, 0);
			visitFieldInsn(GETFIELD, "arc/scene/ui/layout/Cell", "element", "Larc/scene/Element;");
			visitVarInsn(ALOAD, 1);  // Cons
			visitMethodInsn(
			 INVOKESTATIC,
			 dot2slash(UpdateRef.class),
			 "wrap",
			 "(Larc/scene/Element;" + lambdaType + ")" + lambdaType,
			 false
			);
			visitVarInsn(ASTORE, 1);
		}
	}
}
