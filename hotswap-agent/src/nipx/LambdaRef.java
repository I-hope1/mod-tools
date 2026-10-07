package nipx;

import arc.Core;
import arc.Events;
import arc.func.*;
import arc.input.KeyCode;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.event.EventListener;
import arc.scene.ui.*;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.TextField.TextFieldValidator;
import arc.scene.ui.layout.*;
import nipx.ref.UpdateRef;
import nipx.uihook.CellPropertyRef;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.reflect.Field;
import java.util.*;

import static nipx.AnnotationTransformer.*;
import static nipx.HotSwapAgent.*;

/** @see UpdateRef */
public class LambdaRef {

	private static volatile boolean initialized = false;

	public static synchronized void init() {
		if (initialized) return;
		initialized = true;

		String runnableType      = internalName(Runnable.class);
		String boolpType         = internalName(Boolp.class);
		String provType          = internalName(Prov.class);
		String consType          = internalName(Cons.class);
		String floatcType        = internalName(Floatc.class);
		String floatc2Type       = internalName(Floatc2.class);
		String eventListenerType = internalName(EventListener.class);

		String runnableNative      = getDescriptor(Runnable.class);
		String boolpNative         = getDescriptor(Boolp.class);
		String provNative          = getDescriptor(Prov.class);
		String consNative          = getDescriptor(Cons.class);
		String floatcNative        = getDescriptor(Floatc.class);
		String floatc2Native       = getDescriptor(Floatc2.class);
		String eventListenerNative = getDescriptor(EventListener.class);

		String elementType         = getDescriptor(Element.class);
		String inputListenerNative = getDescriptor(InputListener.class);
		String clickListenerNative = getDescriptor(ClickListener.class);
		String keyCodeNative       = getDescriptor(KeyCode.class);

		redefineCell();
		CellPropertyRef.redefineCellProperties();
		redefineEvents();

		// 子类在前面，父类在后
		Injector.redefineTask(Button.class, "setDisabled", "(" + boolpNative + ")V", boolpType, 1, "wrapButtonDisabled");
		Injector.redefineTask(Label.class, "setText", provNative);
		Injector.redefineTask(TextField.class, "setValidator", "(" + getDescriptor(TextFieldValidator.class) + ")V", internalName(TextFieldValidator.class), 1, "wrapValidator");

		Injector.redefineTask(Dialog.class, "shown", "(" + runnableNative + ")V", runnableType, 1, "wrapSilent");
		Injector.redefineTask(Dialog.class, "hidden", "(" + runnableNative + ")V", runnableType, 1, "wrapSilent");
		Injector.redefineTask(Dialog.class, "resizedShown", "(" + runnableNative + ")V", runnableType, 1, "wrapSilent");
		Injector.redefineTask(Dialog.class, "resized", "(" + runnableNative + ")V", runnableType, 1, "wrapSilent");
		Injector.redefineTask(Dialog.class, "resized", "(Z" + runnableNative + ")V", runnableType, 2, "wrapSilent");
		Injector.redefineTask(Dialog.class, "closeOnBack", "(" + runnableNative + ")V", runnableType, 1, "wrapSilent");

		Injector.redefineTask(Element.class, "dragged", "(" + floatc2Native + ")V", floatc2Type, 1, "wrapSilent");
		Injector.redefineTask(Element.class, "scrolled", "(" + floatcNative + ")V", floatcType, 1, "wrapSilent");
		Injector.redefineTask(Element.class, "addListener", "(" + eventListenerNative + ")V", eventListenerType, 1, "wrapListener");
		Injector.redefineTask(Element.class, "removeListener", "(" + eventListenerNative + ")Z", eventListenerType, 1, "unwrapListener");
		Injector.redefineTask(Element.class, "addCaptureListener", "(" + eventListenerNative + ")V", eventListenerType, 1, "wrapCaptureListener");
		Injector.redefineTask(Element.class, "removeCaptureListener", "(" + eventListenerNative + ")Z", eventListenerType, 1, "unwrapCaptureListener");
		Injector.redefineTask(Element.class, "clicked", "(" + runnableNative + ")" + clickListenerNative, runnableType, 1, "wrapSilent");
		Injector.redefineTask(Element.class, "clicked", "(" + keyCodeNative + runnableNative + ")" + clickListenerNative, runnableType, 2, "wrapSilent");
		Injector.redefineTask(Element.class, "clicked", "(" + consNative + runnableNative + ")" + clickListenerNative, runnableType, 2, "wrapSilent");
		Injector.redefineTask(Element.class, "clicked", "(" + consNative + consNative + ")" + clickListenerNative, consType, 2, "wrapSilent");
		Injector.redefineTask(Element.class, "keyDown", "(" + keyCodeNative + runnableNative + ")V", runnableType, 2, "wrapSilent");
		Injector.redefineTask(Element.class, "keyDown", "(" + consNative + ")V", consType, 1, "wrapSilent");
		Injector.redefineTask(Element.class, "tapped", "(" + runnableNative + ")" + inputListenerNative, runnableType, 1, "wrapSilent");
		Injector.redefineTask(Element.class, "hovered", "(" + runnableNative + ")V", runnableType, 1, "wrapSilent");
		Injector.redefineTask(Element.class, "released", "(" + runnableNative + ")V", runnableType, 1, "wrapSilent");
		Injector.redefineTask(Element.class, "changed", "(" + runnableNative + ")V", runnableType, 1, "wrapSilent");
		Injector.redefineTask(Element.class, "update", "(" + runnableNative + ")" + elementType, runnableType, 1, "wrapUpdate");
		Injector.redefineTask(Element.class, "visible", "(" + boolpNative + ")" + elementType, boolpType, 1, "wrapVisible");
		Injector.redefineTask(Element.class, "touchable", "(" + provNative + ")" + elementType, provType, 1, "wrapTouchable");

		Injector.batchProcess();

		// redefineTable();
	}
	//region Events
	private static void redefineEvents() {
		try {
			var bytes = fetchCurrentBytecode(Events.class);
			bytes = injectEvents(bytes);
			Injector.redefineOneClass(Events.class, bytes);
			HotSwapAgent.info("[LambdaRef] Successfully hooked arc.Events (on/run/remove)");
		} catch (Throwable t) {
			HotSwapAgent.error("[LambdaRef] Failed to hook arc.Events: " + t.getMessage(), t);
		}
	}

	private static byte[] injectEvents(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				// 拦截 on(Class, Cons)
				if ("on".equals(name) && "(Ljava/lang/Class;Larc/func/Cons;)V".equals(descriptor)) {
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 0); // type (Class)
					mv.visitVarInsn(Opcodes.ALOAD, 1); // listener (Cons)
					mv.visitFieldInsn(Opcodes.GETSTATIC, "arc/Events", "events", "Larc/struct/ObjectMap;");
					mv.visitMethodInsn(Opcodes.INVOKESTATIC,
					 AnnotationTransformer.internalName(UpdateRef.class),
					 "eventsOn", "(Ljava/lang/Class;Larc/func/Cons;Larc/struct/ObjectMap;)V", false);
					mv.visitInsn(Opcodes.RETURN);
					mv.visitMaxs(0, 0);
					mv.visitEnd();
					return null;
				}
				// 拦截 run(Object, Runnable)
				if ("run".equals(name) && "(Ljava/lang/Object;Ljava/lang/Runnable;)V".equals(descriptor)) {
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 0); // type (Object)
					mv.visitVarInsn(Opcodes.ALOAD, 1); // listener (Runnable)
					mv.visitFieldInsn(Opcodes.GETSTATIC, "arc/Events", "events", "Larc/struct/ObjectMap;");
					mv.visitMethodInsn(Opcodes.INVOKESTATIC,
					 AnnotationTransformer.internalName(UpdateRef.class),
					 "eventsRun", "(Ljava/lang/Object;Ljava/lang/Runnable;Larc/struct/ObjectMap;)V", false);
					mv.visitInsn(Opcodes.RETURN);
					mv.visitMaxs(0, 0);
					mv.visitEnd();
					return null;
				}
				// 拦截 remove(Class, Cons)
				if ("remove".equals(name) && "(Ljava/lang/Class;Larc/func/Cons;)Z".equals(descriptor)) {
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 0); // type (Class)
					mv.visitVarInsn(Opcodes.ALOAD, 1); // listener (Cons)
					mv.visitFieldInsn(Opcodes.GETSTATIC, "arc/Events", "events", "Larc/struct/ObjectMap;");
					mv.visitMethodInsn(Opcodes.INVOKESTATIC,
					 AnnotationTransformer.internalName(UpdateRef.class),
					 "eventsRemove", "(Ljava/lang/Class;Larc/func/Cons;Larc/struct/ObjectMap;)Z", false);
					mv.visitInsn(Opcodes.IRETURN);
					mv.visitMaxs(0, 0);
					mv.visitEnd();
					return null;
				}
				return super.visitMethod(access, name, descriptor, signature, exceptions);
			}
		};
		cr.accept(cv, ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}
	//endregion
	//region Cell
	private static void redefineCell() {
		var bytes = fetchCurrentBytecode(Cell.class);
		bytes = injectCell(bytes);
		Injector.redefineOneClass(Cell.class, bytes);
		// 必须回写缓存：否则紧随其后的 CellPropertyRef.redefineCellProperties()
		// 会因 bytecodeCache 未命中而降级读取纯净原始字节码，从零重建 Cell，
		// 抹掉这里注入的 wrapCellUpdate/Disabled/Tooltip/Checked 钩子。
		bytecodeCache.put(Cell.class.getName(), bytes);
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
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Cons;", "wrapCellUpdate");
				}
				// 拦截 disabled(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;
				if ("disabled".equals(name) && "(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Boolf;", "wrapCellDisabled");
				}
				// 拦截 tooltip(Larc/func/Cons;)Larc/scene/ui/layout/Cell;
				if ("tooltip".equals(name) && "(Larc/func/Cons;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Cons;", "wrapCellTooltip");
				}
				// 拦截 checked(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;
				if ("checked".equals(name) && "(Larc/func/Boolf;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new CellAdviceAdapter(mv, access, name, descriptor, "Larc/func/Boolf;", "wrapCellChecked");
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


	public static void beforeClassRedefined(String slashClassName, byte[] newBytecode) {
		// no-op: see javadoc
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
		private final String wrapMethodName;
		public CellAdviceAdapter(MethodVisitor mv, int access, String name, String descriptor,
		                         String lambdaType, String wrapMethodName) {
			super(Opcodes.ASM9, mv, access, name, descriptor);
			this.lambdaType = lambdaType;
			this.wrapMethodName = wrapMethodName;
		}
		@Override
		protected void onMethodEnter() {
			// UpdateRef.wrapCell*(this, cons)
			visitVarInsn(ALOAD, 0); // Cell
			visitVarInsn(ALOAD, 1); // lambda
			visitMethodInsn(
			 INVOKESTATIC,
			 internalName(UpdateRef.class),
			 wrapMethodName,
			 "(Larc/scene/ui/layout/Cell;" + lambdaType + ")" + lambdaType,
			 false
			);
			visitVarInsn(ASTORE, 1);
		}
	}
}
