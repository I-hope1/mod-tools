package nipx;

import arc.Core;
import arc.func.*;
import arc.input.KeyCode;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.TextField.TextFieldValidator;
import arc.scene.ui.layout.*;
import nipx.ref.UpdateRef;
import org.objectweb.asm.*;
import org.objectweb.asm.Label;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.reflect.Field;
import java.util.*;

import static nipx.AnnotationTransformer.dot2slash;
import static nipx.HotSwapAgent.*;

/** @see UpdateRef */
public class LambdaRef {

	public static void init() {
		String runnableType = dot2slash(Runnable.class);
		String boolpType    = dot2slash(Boolp.class);
		String provType     = dot2slash(Prov.class);
		String consType     = dot2slash(Cons.class);

		String inputListenerType = dot2slash(InputListener.class);

		// 子类在前面，父类在后
		Injector.redefine(Button.class, "setDisabled", boolpType);
		Injector.redefine(Label.class, "setText", provType);
		redefineCell();
		Injector.redefine(TextField.class, "setValidator", dot2slash(TextFieldValidator.class));

		Injector.redefine(Element.class, "clicked", "(L" + runnableType + ";)L" + dot2slash(ClickListener.class) + ";", runnableType);
		Injector.redefine(Element.class, "clicked", "(L" + dot2slash(KeyCode.class) + ";L" + runnableType + ";)L" + dot2slash(ClickListener.class) + ";", runnableType, 1);
		Injector.redefine(Element.class, "clicked", "(L" + consType + ";L" + runnableType + ";)L" + dot2slash(ClickListener.class) + ";", runnableType, 1);
		Injector.redefine(Element.class, "clicked", "(L" + consType + ";L" + consType + ";)L" + dot2slash(ClickListener.class) + ";", dot2slash(Cons.class), 1);
		Injector.redefine(Element.class, "keyDown", "(L" + runnableType + ";)V", consType);
		Injector.redefine(Element.class, "tapped", "(L" + runnableType + ";)L" + inputListenerType + ";", runnableType);
		Injector.redefine(Element.class, "hovered", "(L" + runnableType + ";)L" + inputListenerType + ";", runnableType);
		Injector.redefine(Element.class, "released", "(L" + runnableType + ";)L" + inputListenerType + ";", runnableType);
		Injector.redefine(Element.class, "changed", "(L" + runnableType + ";)V", runnableType);
		Injector.redefine(Element.class, "update", "(L" + runnableType + ";)L" + Injector.CL_ELEMENT + ";", runnableType);
		Injector.redefine(Element.class, "visible", "(L" + boolpType + ";)L" + Injector.CL_ELEMENT + ";", boolpType);
		Injector.redefine(Element.class, "touchable", "(L" + provType + ";)L" + Injector.CL_ELEMENT + ";", provType);

		Injector.batchProcess();
		// redefineTable();
	}
	//region Cell
	private static void redefineCell() {
		var bytes = fetchOriginalBytecode(Cell.class);
		bytes = injectCell(bytes);
		Injector.redefineOneClass(Cell.class, bytes);
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
				// 拦截 update(Larc/func/Cons;)Larc/scene/ui/layout/Cell;
				if ("update".equals(name) && "(Larc/func/Cons;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Cons;");
				}
				// 拦截 disabled(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;
				if ("disabled".equals(name) && "(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Boolf;");
				}
				// 拦截 tooltip(Larc/func/Cons;)Larc/scene/ui/layout/Cell;
				if ("tooltip".equals(name) && "(Larc/func/Cons;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Cons;");
				}
				// 拦截 checked(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;
				if ("checked".equals(name) && "(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
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
	private static void redefineTable() {
		Class<Table> tableClass = Table.class;
		byte[]       bytes      = fetchOriginalBytecode(tableClass);
		ClassReader  cr         = new ClassReader(bytes);
		ClassWriter  cw         = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			boolean isTable = false;

			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				super.visit(version, access, name, signature, superName, interfaces);
				if (name.equals("arc/scene/ui/Table")) {
					isTable = true;
					// 1. 注入字段: public Cons rebuildCons;
					super.visitField(Opcodes.ACC_PUBLIC, "rebuildCons", "Larc/func/Cons;", null, null).visitEnd();
				}
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				// 2. 拦截 Table(Cons) 构造函数
				if (isTable && "<init>".equals(name) && "(Larc/func/Cons;)V".equals(descriptor)) {
					return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodEnter() {
							// this.rebuildCons = cons; (参数1是Cons)
							visitVarInsn(ALOAD, 0);
							visitVarInsn(ALOAD, 1);
							visitFieldInsn(PUTFIELD, "arc/scene/ui/Table", "rebuildCons", "Larc/func/Cons;");
						}
					};
				}
				return mv;
			}
		};
		cr.accept(cv, ClassReader.EXPAND_FRAMES);
		Injector.redefineOneClass(tableClass, cw.toByteArray());
	}

	//endregion


	/**
	 * 当某个类被 HotSwap 重载时调用。
	 * 清除所有 UpdateRef 中来自该类的 lambda，避免 NoSuchMethodError。
	 * @param dotClassName 被重载的类名，如 com.example.MyView
	 */
	public static void onClassRedefined(String dotClassName) {
		/* int cleared = 0;
		for (var ref : UpdateRef.getAll()) {
			UpdateRef updateRef = ref.get();
			if (updateRef != null && updateRef.clearIfFromClass(dotClassName)) {
				cleared++;
			}
		} */
		long cleared = UpdateRef.getAll().stream().filter(ref -> ref.get() == null).count();
		UpdateRef.clearLambda();

		if (cleared > 0) {
			info("[LambdaRef] Cleared " + cleared + " UpdateRef lambda(s) from " + dotClassName);
		}

		// if (Core.app != null && Core.scene != null) {
		// 	Core.app.post(() -> reloadAffectedTables(Collections.singleton(dotClassName)));
		// }
	}

	//region ReloadTable
	/** 精确刷新受影响的 Table */
	@SuppressWarnings("JavaReflectionMemberAccess")
	public static void reloadAffectedTables(Set<String> modifiedClassNames) {
		Group root = Core.scene.root;
		if (root == null || modifiedClassNames.isEmpty()) return;

		List<Table> targets = new ArrayList<>();
		collectAffectedTables(root, targets, modifiedClassNames);

		// 3. 统一执行更新
		for (Table t : targets) {
			try {
				Field consField = Table.class.getField("rebuildCons");
				@SuppressWarnings("unchecked")
				var cons = (Cons<Table>) consField.get(t);

				if (cons != null) {
					t.clear();      // 清空旧的子节点
					cons.get(t);    // 重新运行构建 Lambda (此时执行的是 DCEVM 替换后的新逻辑)
					t.pack();       // 重新计算尺寸
				}
			} catch (Exception ignored) {
			}
		}
	}

	/** 递归收集方法：带有【顶层阻断】优化 */
	@SuppressWarnings("JavaReflectionMemberAccess")
	private static void collectAffectedTables(Element element, List<Table> targets, Set<String> modifiedClassNames) {
		// 如果是 Table，检查它的 Lambda 是否属于刚刚修改的类
		if (element instanceof Table) {
			try {
				Field  consField = Table.class.getField("rebuildCons");
				Object cons      = consField.get(element);

				if (cons != null) {
					String hostClass = Injector.getHostClassName(cons);
					if (modifiedClassNames.contains(hostClass)) {
						targets.add((Table) element);
						// 【优化3：顶层阻断】
						// 核心！如果父 Table 已经被标记为需要重建，就绝对不要再往下深入子节点了！
						// 因为父 Table 重建时自然会重新生成全新的子 Table。避免重复执行导致报错。
						return;
					}
				}
			} catch (Exception ignored) {
			}
		}

		// 如果当前节点没被匹配，才继续递归它的子节点
		if (element instanceof Group group) {
			for (Element child : group.getChildren()) {
				collectAffectedTables(child, targets, modifiedClassNames);
			}
		}
	}

	//endregion
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
			 "(L" + Injector.CL_ELEMENT + ";" + lambdaType + ")" + lambdaType,
			 false
			);
			visitVarInsn(ASTORE, 1);
		}
	}
}
