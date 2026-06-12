package nipx.ref;

import nipx.*;
import nipx.ClassDiffUtil.ClassDiff;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class InitFix {
	private static final String PATCH_METHOD = "$hotswap$initNewFields$";

	public static byte[] injectFieldInitPatch(
	 byte[] newBytes, String className, Set<String> addedFields) {

		ClassNode newClass = new ClassNode();
		new ClassReader(newBytes).accept(newClass, 0);

		List<AbstractInsnNode> initInsns = extractFieldInits(newClass, addedFields);
		if (initInsns.isEmpty()) return newBytes;

		// 生成静态方法: static void $hotswap$initNewFields$(TargetClass instance)
		String desc = "(L" + className + ";)V";
		MethodNode patch = new MethodNode(
		 Opcodes.ACC_STATIC,
		 PATCH_METHOD, desc, null, null
		);

		// 把提取的指令翻译：ALOAD_0 → ALOAD_0（参数0就是instance，一致）
		for (AbstractInsnNode insn : initInsns) {
			patch.instructions.add(insn);
		}
		patch.instructions.add(new InsnNode(Opcodes.RETURN));

		newClass.methods.add(patch);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		newClass.accept(cw);
		return cw.toByteArray();
	}
	public static byte[] transform(byte[] newBytes, ClassDiff diff) {
		if (!HotSwapAgent.HOTSWAP_PLUS) return newBytes;
		// diff.changedFields 里 "+ fieldName" 就是新增字段
		Set<String> addedFields = diff.changedFields.stream()
		 .filter(s -> s.startsWith("+ "))
		 .map(s -> s.substring(2))
		 .collect(Collectors.toSet());
		// extractFieldInits(diff.newClass, addedFields);
		return injectFieldInitPatch(newBytes, diff.newClass.name, addedFields);
	}
	public static void afterRedefined(Class<?> clazz) {
		if (!HotSwapAgent.HOTSWAP_PLUS) return;

		List<?> instances = InstanceTracker.getInstances(clazz);
		if (instances.isEmpty()) return;
		HotSwapAgent.info("Applying field init patch to " + clazz + " (" + instances + ")");
		applyFieldInitToInstances(clazz, instances);
	}

	public static void applyFieldInitToInstances(
	 Class<?> clazz, List<?> existingInstances) {
		try {
			String desc  = "(L" + clazz.getName().replace('.', '/') + ";)V";
			Method patch = clazz.getDeclaredMethod(PATCH_METHOD, clazz);
			patch.setAccessible(true);
			for (Object instance : existingInstances) {
				patch.invoke(null, instance);
			}
		} catch (NoSuchMethodException e) {
			// 没有新增字段，跳过
		} catch (Exception e) {
			HotSwapAgent.error("Field init patch failed: " + e.getMessage());
		}
	}
	private static List<AbstractInsnNode> extractFieldInits(
	 ClassNode newClass, Set<String> addedFields) {

		// 优先找无参构造
		MethodNode init = newClass.methods.stream()
		 .filter(m -> "<init>".equals(m.name) && "()V".equals(m.desc))
		 .findFirst().orElse(null);
		if (init == null) return Collections.emptyList();

		List<AbstractInsnNode> result = new ArrayList<>();
		InsnList               insns  = init.instructions;

		int i = 0;
		while (i < insns.size()) {
			AbstractInsnNode insn = insns.get(i);

			// 跳过 super.<init> 调用
			if (insn instanceof MethodInsnNode m
			    && "<init>".equals(m.name)
			    && insn.getOpcode() == Opcodes.INVOKESPECIAL) {
				i++;
				continue;
			}

			// 识别 PUTFIELD 到新增字段
			if (insn instanceof FieldInsnNode f
			    && insn.getOpcode() == Opcodes.PUTFIELD
			    && addedFields.contains(f.name)) {
				// 往前回溯，把 ALOAD_0 + 值加载指令一起收集
				// 找到这条 PUTFIELD 之前对应的 ALOAD_0 起点
				int start = i - 1;
				while (start > 0
				       && !(insns.get(start) instanceof VarInsnNode v
				            && v.getOpcode() == Opcodes.ALOAD
				            && v.var == 0)) {
					start--;
				}
				for (int j = start; j <= i; j++) {
					result.add(insns.get(j).clone(new HashMap<>()));
				}
			}
			i++;
		}
		return result;
	}
}
